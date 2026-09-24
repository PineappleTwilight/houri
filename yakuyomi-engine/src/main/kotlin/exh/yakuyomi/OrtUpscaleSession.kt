package exh.yakuyomi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.os.Build
import java.nio.FloatBuffer
import java.util.EnumSet

/** ONNX Runtime session used by the NNAPI upscaler path. */
class OrtUpscaleSession private constructor(
    private val environment: OrtEnvironment,
    private val modelPath: String,
    private val config: Config,
    private var session: OrtSession,
    private var inputName: String,
    private var outputName: String,
    usedNnapi: Boolean,
) : AutoCloseable {
    data class Config(
        val nativeScale: Int,
        val tileSize: Int = 128,
        val padding: Int = 16,
        val inputOrder: Int = 0,
        val outputOrder: Int = 0,
    )

    @Volatile
    var usedNnapi: Boolean = usedNnapi
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun process(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (bitmap.isRecycled || targetWidth <= 0 || targetHeight <= 0) return null
        return try {
            processOnce(bitmap, targetWidth, targetHeight)
        } catch (error: Throwable) {
            if (!usedNnapi) {
                lastError = error.message
                null
            } else {
                // NNAPI graph compilation can fail on a particular device/model even
                // though the runtime is present. Recreate the same model on ORT CPU.
                runCatching {
                    session.close()
                    val replacement = createSession(environment, modelPath, config, useNnapi = false)
                    session = replacement.first
                    inputName = replacement.second
                    outputName = replacement.third
                    usedNnapi = false
                    lastError = "NNAPI fallback: ${error.message}"
                }.onFailure {
                    lastError = "${error.message}; CPU fallback: ${it.message}"
                    return null
                }
                try {
                    processOnce(bitmap, targetWidth, targetHeight)
                } catch (cpuError: Throwable) {
                    lastError = cpuError.message
                    null
                }
            }
        }
    }

    private fun processOnce(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null
        return try {
            val input = IntArray(source.width * source.height)
            source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
            val output = IntArray(targetWidth * targetHeight)
            val safeTile = config.tileSize.coerceIn(32, 512)
            val safePadding = config.padding.coerceIn(0, 64)
            val scale = config.nativeScale.coerceIn(1, 4)

            for (y0 in 0 until source.height step safeTile) {
                val coreHeight = minOf(safeTile, source.height - y0)
                for (x0 in 0 until source.width step safeTile) {
                    val coreWidth = minOf(safeTile, source.width - x0)
                    val tensor = createInputTensor(source, input, x0, y0, coreWidth, coreHeight, safePadding)
                    tensor.use {
                        session.run(mapOf(inputName to it)).use { result ->
                            val value = (result.get(outputName).orElse(null) ?: result.get(0)) as? OnnxTensor ?: return null
                            val info = value.info as? TensorInfo ?: return null
                            val shape = info.shape
                            if (shape.size != 4 || shape[0] != 1L || shape[1] != 3L) return null
                            val tileOutputWidth = shape[3].toInt()
                            val tileOutputHeight = shape[2].toInt()
                            val coreOutputWidth = coreWidth * scale
                            val coreOutputHeight = coreHeight * scale
                            if (tileOutputWidth < coreOutputWidth || tileOutputHeight < coreOutputHeight) return null
                            val paddedOutputWidth = (coreWidth + safePadding * 2) * scale
                            val paddedOutputHeight = (coreHeight + safePadding * 2) * scale
                            val sourceX = when {
                                tileOutputWidth >= paddedOutputWidth -> safePadding * scale
                                tileOutputWidth >= coreOutputWidth -> (tileOutputWidth - coreOutputWidth) / 2
                                else -> 0
                            }
                            val sourceY = when {
                                tileOutputHeight >= paddedOutputHeight -> safePadding * scale
                                tileOutputHeight >= coreOutputHeight -> (tileOutputHeight - coreOutputHeight) / 2
                                else -> 0
                            }
                            val values = FloatArray(tileOutputWidth * tileOutputHeight * 3)
                            value.floatBuffer.duplicate().apply { rewind() }.get(values)
                            copyOutput(
                                values = values,
                                sourceWidth = tileOutputWidth,
                                sourceHeight = tileOutputHeight,
                                sourceX = sourceX,
                                sourceY = sourceY,
                                outputOrder = config.outputOrder,
                                coreWidth = coreOutputWidth,
                                coreHeight = coreOutputHeight,
                                destination = output,
                                destinationWidth = targetWidth,
                                destinationHeight = targetHeight,
                                destinationX = x0 * scale,
                                destinationY = y0 * scale,
                                input = input,
                                inputWidth = source.width,
                                inputHeight = source.height,
                            )
                        }
                    }
                }
            }
            Bitmap.createBitmap(output, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    private fun createInputTensor(
        source: Bitmap,
        pixels: IntArray,
        x0: Int,
        y0: Int,
        coreWidth: Int,
        coreHeight: Int,
        padding: Int,
    ): OnnxTensor {
        val tileWidth = coreWidth + padding * 2
        val tileHeight = coreHeight + padding * 2
        val values = FloatArray(3 * tileWidth * tileHeight)
        val area = tileWidth * tileHeight
        for (y in 0 until tileHeight) {
            val sourceY = (y0 + y - padding).coerceIn(0, source.height - 1)
            for (x in 0 until tileWidth) {
                val sourceX = (x0 + x - padding).coerceIn(0, source.width - 1)
                val argb = pixels[sourceY * source.width + sourceX]
                val index = y * tileWidth + x
                values[index] = colorChannel(argb, config.inputOrder, 0) / 255f
                values[area + index] = colorChannel(argb, config.inputOrder, 1) / 255f
                values[2 * area + index] = colorChannel(argb, config.inputOrder, 2) / 255f
            }
        }
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(values),
            longArrayOf(1, 3, tileHeight.toLong(), tileWidth.toLong()),
        )
    }

    private fun copyOutput(
        values: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceX: Int,
        sourceY: Int,
        outputOrder: Int,
        coreWidth: Int,
        coreHeight: Int,
        destination: IntArray,
        destinationWidth: Int,
        destinationHeight: Int,
        destinationX: Int,
        destinationY: Int,
        input: IntArray,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        val area = sourceWidth * sourceHeight
        for (y in 0 until coreHeight) {
            val globalY = destinationY + y
            if (globalY !in 0 until destinationHeight) continue
            for (x in 0 until coreWidth) {
                val globalX = destinationX + x
                if (globalX !in 0 until destinationWidth) continue
                val index = (sourceY + y) * sourceWidth + sourceX + x
                val safeIndex = index.coerceIn(0, area - 1)
                val channel0 = values[safeIndex]
                val channel1 = values[area + safeIndex]
                val channel2 = values[2 * area + safeIndex]
                val r = if (outputOrder == 0) channel0 else channel2
                val g = channel1
                val b = if (outputOrder == 0) channel2 else channel0
                val inputX = (globalX.toFloat() * inputWidth / destinationWidth).toInt().coerceIn(0, inputWidth - 1)
                val inputY = (globalY.toFloat() * inputHeight / destinationHeight).toInt().coerceIn(0, inputHeight - 1)
                val alpha = (input[inputY * inputWidth + inputX] ushr 24) and 0xff
                destination[globalY * destinationWidth + globalX] =
                    (alpha shl 24) or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** True when the shared ORT native runtime can be loaded on this device. */
        fun isOrtRuntimeAvailable(): Boolean = runCatching {
            OrtEnvironment.getEnvironment()
            true
        }.getOrDefault(false)

        /** True only when the NNAPI execution provider is actually registered. */
        fun isRuntimeAvailable(): Boolean = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                OrtEnvironment.getAvailableProviders().any {
                    it.name.equals("NnapiExecutionProvider", ignoreCase = true)
                }
        }.getOrDefault(false)

        fun open(
            modelPath: String,
            config: Config,
            preferNnapi: Boolean,
        ): OrtUpscaleSession? {
            val environment = runCatching { OrtEnvironment.getEnvironment() }.getOrNull() ?: return null
            if (preferNnapi && isRuntimeAvailable()) {
                runCatching { createSession(environment, modelPath, config, useNnapi = true) }
                    .getOrNull()
                    ?.let { (session, inputName, outputName) ->
                        return OrtUpscaleSession(environment, modelPath, config, session, inputName, outputName, true)
                    }
            }
            return runCatching { createSession(environment, modelPath, config, useNnapi = false) }
                .getOrNull()
                ?.let { (session, inputName, outputName) ->
                    OrtUpscaleSession(environment, modelPath, config, session, inputName, outputName, false)
                }
        }

        private fun createSession(
            environment: OrtEnvironment,
            modelPath: String,
            config: Config,
            useNnapi: Boolean,
        ): Triple<OrtSession, String, String> {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8))
            options.setInterOpNumThreads(1)
            if (useNnapi) {
                runCatching {
                    options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                }.getOrElse {
                    options.addNnapi()
                }
            }
            return try {
                val session = environment.createSession(modelPath, options)
                val input = session.inputNames.firstOrNull() ?: error("ONNX model has no input")
                val output = session.outputNames.firstOrNull() ?: error("ONNX model has no output")
                Triple(session, input, output)
            } finally {
                options.close()
            }
        }
    }
}

private fun colorChannel(argb: Int, order: Int, channel: Int): Int {
    val shift = if (order == 0) {
        when (channel) {
            0 -> 16
            1 -> 8
            else -> 0
        }
    } else {
        when (channel) {
            0 -> 0
            1 -> 8
            else -> 16
        }
    }
    return (argb ushr shift) and 0xff
}

private fun toByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
