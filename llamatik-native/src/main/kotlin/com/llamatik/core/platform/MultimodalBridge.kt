package com.llamatik.library.platform

/**
 * Vision-language bridge: loads a vision GGUF together with its `mmproj` projector and
 * answers prompts about an image.
 *
 * Vendored from the Llamatik fork; see [LlamaBridge] for the native contract.
 */
object MultimodalBridge {

    init {
        System.loadLibrary("llama_jni")
    }

    /** Loads the model and projector. Both files must exist before calling this. */
    external fun initModel(modelPath: String, mmprojPath: String): Boolean

    /** Cancels an in-progress [analyzeImageBytesStream]. */
    external fun cancelAnalysis()

    /** Frees the native context. */
    external fun release()

    private external fun nativeAnalyzeImageBytesStream(
        imageBytes: ByteArray,
        prompt: String,
        callback: GenStream,
    )

    /**
     * Answers [prompt] about [imageBytes] (JPEG/PNG/BMP), streaming the response to
     * [callback]. Blocks the calling thread until generation completes.
     */
    fun analyzeImageBytesStream(imageBytes: ByteArray, prompt: String, callback: GenStream) {
        nativeAnalyzeImageBytesStream(imageBytes, prompt, callback)
    }
}
