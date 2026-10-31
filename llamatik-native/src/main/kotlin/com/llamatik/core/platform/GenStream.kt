package com.llamatik.library.platform

/**
 * Streaming callback handed to the native bridge.
 *
 * Native code calls these methods by name through JNI, so the names and the
 * [Any]-returning signatures must stay as they are (see `consumer-rules.pro`).
 */
interface GenStream {
    fun onDelta(text: String)

    fun onComplete()

    fun onError(message: String)
}
