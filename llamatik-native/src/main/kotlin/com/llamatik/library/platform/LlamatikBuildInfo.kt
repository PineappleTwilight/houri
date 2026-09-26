package com.llamatik.library.platform

import android.os.Build

/**
 * What was compiled into the native runtime, so callers can tell whether `gpuLayers`
 * can do anything at all without having to guess from logcat.
 */
object LlamatikBuildInfo {

    /**
     * True when *this device's* native library was built with a ggml GPU backend, i.e.
     * `gpuLayers` is meaningful.
     *
     * Deliberately not just the build flag: the Vulkan backend is only compiled for 64-bit
     * ABIs (arm64-v8a/x86_64/riscv64), because ggml-vulkan does not compile for 32-bit at
     * this llama.cpp revision — see the per-ABI gate in the module's `CMakeLists.txt`. A
     * 32-bit device therefore loads a CPU-only `libllama_jni.so` even from a build where GPU
     * offload was requested, and reporting the raw flag would leave the UI offering a
     * `gpuLayers` slider that silently does nothing.
     *
     * The primary ABI is the right proxy: Android maps the library matching the device's
     * preferred ABI, and this app ships all four ABIs, so `SUPPORTED_ABIS[0]` names the ABI
     * whose `.so` is actually loaded.
     */
    val gpuOffloadCompiledIn: Boolean
        get() = BuildConfig.GPU_OFFLOAD_COMPILED_IN && isPrimaryAbi64Bit

    private val isPrimaryAbi64Bit: Boolean
        get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a", "x86_64", "riscv64" -> true
            else -> false
        }
}
