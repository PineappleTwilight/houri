#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>
#include <vector>
#include "gpu.h"
#include "net.h"

namespace {

constexpr const char* kTag = "HouriUpscaler";
constexpr int kMaxDimension = 8192;
constexpr int64_t kMaxPixels = 16LL * 1024LL * 1024LL;

struct UpscaleSession {
    ncnn::Net net;
    int nativeScale = 2;
    int inputOrder = 0; // 0 = RGB, 1 = BGR
    int outputOrder = 1; // NCNN super-resolution weights commonly emit BGR.
    int inputIndex = 0;
    int outputIndex = 0;
#if NCNN_VULKAN
    ncnn::VulkanDevice* vulkanDevice = nullptr;
#endif
};

int clampInt(int value, int minValue, int maxValue) {
    return std::max(minValue, std::min(maxValue, value));
}

unsigned char toByte(float value) {
    // The selected NCNN models emit normalized RGB/BGR. Accept an already
    // byte-scaled output as a defensive fallback for converted models.
    if (value > 1.5f) value = std::min(255.0f, value);
    else value *= 255.0f;
    return static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, value)) + 0.5f);
}

void fillInputTile(
    const int32_t* pixels,
    int width,
    int height,
    int x0,
    int y0,
    int coreWidth,
    int coreHeight,
    int padding,
    int inputOrder,
    ncnn::Mat& tile) {
    const int tileWidth = coreWidth + padding * 2;
    const int tileHeight = coreHeight + padding * 2;
    for (int c = 0; c < 3; ++c) {
        float* dst = tile.channel(c);
        for (int y = 0; y < tileHeight; ++y) {
            const int sourceY = clampInt(y0 + y - padding, 0, height - 1);
            float* row = dst + static_cast<size_t>(y) * tileWidth;
            for (int x = 0; x < tileWidth; ++x) {
                const int sourceX = clampInt(x0 + x - padding, 0, width - 1);
                const uint32_t argb = static_cast<uint32_t>(pixels[static_cast<size_t>(sourceY) * width + sourceX]);
                const int shift = inputOrder == 0
                    ? (c == 0 ? 16 : (c == 1 ? 8 : 0)) // R, G, B
                    : (c == 0 ? 0 : (c == 1 ? 8 : 16));  // B, G, R
                row[x] = static_cast<float>((argb >> shift) & 0xff) / 255.0f;
            }
        }
    }
}

bool copyOutputTile(
    const ncnn::Mat& output,
    int coreWidth,
    int coreHeight,
    int padding,
    int nativeScale,
    int outputOrder,
    unsigned char* globalRgba,
    int globalWidth,
    int globalHeight,
    int destinationX,
    int destinationY,
    int sourceWidth,
    int sourceHeight) {
    const int coreOutputWidth = coreWidth * nativeScale;
    const int coreOutputHeight = coreHeight * nativeScale;
    if (output.w < coreOutputWidth || output.h < coreOutputHeight || output.c < 3) return false;

    const int paddedOutputWidth = (coreWidth + padding * 2) * nativeScale;
    const int paddedOutputHeight = (coreHeight + padding * 2) * nativeScale;
    int sourceX = 0;
    int sourceY = 0;
    if (output.w >= paddedOutputWidth && output.h >= paddedOutputHeight) {
        sourceX = padding * nativeScale;
        sourceY = padding * nativeScale;
    } else if (output.w >= coreOutputWidth && output.h >= coreOutputHeight) {
        sourceX = (output.w - coreOutputWidth) / 2;
        sourceY = (output.h - coreOutputHeight) / 2;
    }
    if (sourceX + coreOutputWidth > output.w || sourceY + coreOutputHeight > output.h) return false;

    const int copyWidth = std::min(coreOutputWidth, sourceWidth);
    const int copyHeight = std::min(coreOutputHeight, sourceHeight);
    for (int y = 0; y < copyHeight; ++y) {
        const int globalY = destinationY + y;
        if (globalY < 0 || globalY >= globalHeight) continue;
        for (int x = 0; x < copyWidth; ++x) {
            const int globalX = destinationX + x;
            if (globalX < 0 || globalX >= globalWidth) continue;
            const int outputX = sourceX + x;
            const int outputY = sourceY + y;
            const float r = outputOrder == 0
                ? output.channel(0).row(outputY)[outputX]
                : output.channel(2).row(outputY)[outputX];
            const float g = output.channel(1).row(outputY)[outputX];
            const float b = outputOrder == 0
                ? output.channel(2).row(outputY)[outputX]
                : output.channel(0).row(outputY)[outputX];
            const size_t outIndex = (static_cast<size_t>(globalY) * globalWidth + globalX) * 4;
            globalRgba[outIndex] = toByte(r);
            globalRgba[outIndex + 1] = toByte(g);
            globalRgba[outIndex + 2] = toByte(b);
        }
    }
    return true;
}

float sampleChannel(const unsigned char* rgba, int width, int height, int x, int y, int channel) {
    x = clampInt(x, 0, width - 1);
    y = clampInt(y, 0, height - 1);
    return static_cast<float>(rgba[(static_cast<size_t>(y) * width + x) * 4 + channel]) / 255.0f;
}

void resampleRgba(
    const unsigned char* source,
    int sourceWidth,
    int sourceHeight,
    int32_t* destination,
    int destinationWidth,
    int destinationHeight,
    const int32_t* inputPixels,
    int inputWidth,
    int inputHeight) {
    for (int y = 0; y < destinationHeight; ++y) {
        const double sourceY = ((y + 0.5) * sourceHeight / destinationHeight) - 0.5;
        const int y0 = static_cast<int>(std::floor(sourceY));
        const int y1 = y0 + 1;
        const double fy = sourceY - y0;
        for (int x = 0; x < destinationWidth; ++x) {
            const double sourceX = ((x + 0.5) * sourceWidth / destinationWidth) - 0.5;
            const int x0 = static_cast<int>(std::floor(sourceX));
            const int x1 = x0 + 1;
            const double fx = sourceX - x0;
            const double w00 = (1.0 - fx) * (1.0 - fy);
            const double w10 = fx * (1.0 - fy);
            const double w01 = (1.0 - fx) * fy;
            const double w11 = fx * fy;
            const double r = sampleChannel(source, sourceWidth, sourceHeight, x0, y0, 0) * w00 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y0, 0) * w10 +
                sampleChannel(source, sourceWidth, sourceHeight, x0, y1, 0) * w01 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y1, 0) * w11;
            const double g = sampleChannel(source, sourceWidth, sourceHeight, x0, y0, 1) * w00 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y0, 1) * w10 +
                sampleChannel(source, sourceWidth, sourceHeight, x0, y1, 1) * w01 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y1, 1) * w11;
            const double b = sampleChannel(source, sourceWidth, sourceHeight, x0, y0, 2) * w00 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y0, 2) * w10 +
                sampleChannel(source, sourceWidth, sourceHeight, x0, y1, 2) * w01 +
                sampleChannel(source, sourceWidth, sourceHeight, x1, y1, 2) * w11;
            const int alphaX = clampInt(static_cast<int>((static_cast<double>(x) + 0.5) * inputWidth / destinationWidth), 0, inputWidth - 1);
            const int alphaY = clampInt(static_cast<int>((static_cast<double>(y) + 0.5) * inputHeight / destinationHeight), 0, inputHeight - 1);
            const int alpha = static_cast<int>((static_cast<uint32_t>(inputPixels[static_cast<size_t>(alphaY) * inputWidth + alphaX]) >> 24) & 0xff);
            destination[static_cast<size_t>(y) * destinationWidth + x] =
                (alpha << 24) | (static_cast<int>(r * 255.0 + 0.5) << 16) |
                    (static_cast<int>(g * 255.0 + 0.5) << 8) | static_cast<int>(b * 255.0 + 0.5);
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_exh_yakuyomi_NativeUpscaler_nativeGpuCount(JNIEnv*, jobject) {
#if NCNN_VULKAN
    return ncnn::get_gpu_count();
#else
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_exh_yakuyomi_NativeUpscaler_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring paramPath,
    jstring binPath,
    jint backend,
    jint nativeScale,
    jint inputOrder,
    jint outputOrder,
    jint threads) {
    if (paramPath == nullptr || binPath == nullptr) return 0;
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    if (param == nullptr || bin == nullptr) {
        if (param != nullptr) env->ReleaseStringUTFChars(paramPath, param);
        if (bin != nullptr) env->ReleaseStringUTFChars(binPath, bin);
        return 0;
    }

    auto* session = new (std::nothrow) UpscaleSession();
    if (session == nullptr) {
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        return 0;
    }
    session->nativeScale = clampInt(nativeScale, 1, 4);
    session->inputOrder = inputOrder == 1 ? 1 : 0;
    session->outputOrder = outputOrder == 0 ? 0 : 1;
    session->net.opt.num_threads = clampInt(threads > 0 ? threads : 2, 1, 8);
    session->net.opt.use_winograd_convolution = true;
    session->net.opt.use_sgemm_convolution = true;
    session->net.opt.use_local_pool_allocator = true;
    session->net.opt.use_packing_layout = true;

#if NCNN_VULKAN
    if (backend == 1) {
        const int gpuCount = ncnn::get_gpu_count();
        if (gpuCount <= 0) {
            delete session;
            env->ReleaseStringUTFChars(paramPath, param);
            env->ReleaseStringUTFChars(binPath, bin);
            return 0;
        }
        session->vulkanDevice = ncnn::get_gpu_device(0);
        session->net.opt.use_vulkan_compute = true;
        session->net.set_vulkan_device(session->vulkanDevice);
    }
#else
    (void)backend;
#endif

    const int paramResult = session->net.load_param(param);
    const int modelResult = paramResult == 0 ? session->net.load_model(bin) : -1;
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);
    if (paramResult != 0 || modelResult != 0 || session->net.input_indexes().empty() || session->net.output_indexes().empty()) {
        delete session;
        return 0;
    }
    session->inputIndex = session->net.input_indexes().front();
    session->outputIndex = session->net.output_indexes().back();
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_exh_yakuyomi_NativeUpscaler_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<UpscaleSession*>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_exh_yakuyomi_NativeUpscaler_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jintArray inputPixels,
    jint width,
    jint height,
    jint targetWidth,
    jint targetHeight,
    jint tileSize,
    jint padding,
    jintArray outputPixels) {
    auto* session = reinterpret_cast<UpscaleSession*>(handle);
    if (session == nullptr || inputPixels == nullptr || outputPixels == nullptr) return -1;
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return -2;
    if (width > kMaxDimension || height > kMaxDimension || targetWidth > kMaxDimension || targetHeight > kMaxDimension) return -3;
    if (static_cast<int64_t>(width) * height > kMaxPixels || static_cast<int64_t>(targetWidth) * targetHeight > kMaxPixels) return -4;

    const jsize inputCount = env->GetArrayLength(inputPixels);
    const jsize outputCount = env->GetArrayLength(outputPixels);
    if (inputCount < width * height || outputCount < targetWidth * targetHeight) return -5;

    const int nativeWidth = width * session->nativeScale;
    const int nativeHeight = height * session->nativeScale;
    if (nativeWidth > kMaxDimension || nativeHeight > kMaxDimension ||
        static_cast<int64_t>(nativeWidth) * nativeHeight > kMaxPixels) return -6;

    std::vector<jint> input(static_cast<size_t>(width) * height);
    env->GetIntArrayRegion(inputPixels, 0, input.size(), input.data());
    std::vector<unsigned char> nativeRgba(static_cast<size_t>(nativeWidth) * nativeHeight * 4, 0);
    const int safeTile = clampInt(tileSize, 32, 512);
    const int safePadding = clampInt(padding, 0, 64);
    for (int y0 = 0; y0 < height; y0 += safeTile) {
        const int coreHeight = std::min(safeTile, height - y0);
        for (int x0 = 0; x0 < width; x0 += safeTile) {
            const int coreWidth = std::min(safeTile, width - x0);
            ncnn::Mat tile(coreWidth + safePadding * 2, coreHeight + safePadding * 2, 3);
            fillInputTile(
                input.data(), width, height, x0, y0, coreWidth, coreHeight,
                safePadding, session->inputOrder, tile);
            ncnn::Extractor extractor = session->net.create_extractor();
            if (extractor.input(session->inputIndex, tile) != 0) return -7;
            ncnn::Mat output;
            if (extractor.extract(session->outputIndex, output) != 0 || output.empty()) return -8;
            if (!copyOutputTile(
                    output,
                    coreWidth,
                    coreHeight,
                    safePadding,
                    session->nativeScale,
                    session->outputOrder,
                    nativeRgba.data(),
                    nativeWidth,
                    nativeHeight,
                    x0 * session->nativeScale,
                    y0 * session->nativeScale,
                    coreWidth * session->nativeScale,
                    coreHeight * session->nativeScale)) {
                return -9;
            }
        }
    }
    std::vector<jint> output(static_cast<size_t>(targetWidth) * targetHeight);
    resampleRgba(
        nativeRgba.data(), nativeWidth, nativeHeight, output.data(),
        targetWidth, targetHeight, input.data(), width, height);
    env->SetIntArrayRegion(outputPixels, 0, output.size(), output.data());
    return 0;
}
