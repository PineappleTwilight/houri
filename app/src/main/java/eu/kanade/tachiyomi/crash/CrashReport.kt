package eu.kanade.tachiyomi.crash

import kotlinx.serialization.Serializable

/**
 * Structured crash report — single responsibility: data, not I/O.
 * Previously [eu.kanade.tachiyomi.util.CrashLogUtil] and [GlobalExceptionHandler]
 * mixed string building, file I/O, and intent launching in one place,
 * making crash reporting untestable and lossy (only `stackTraceToString` was saved).
 *
 * This model preserves type, message, full stack, cause chain, suppressed,
 * thread, and a serialized debug block so the report survives process death
 * even if `Json.encodeToString(ThrowableSerializer)` truncates.
 */
@Serializable
data class CrashReport(
    val timestampMs: Long,
    val threadName: String,
    val type: String,
    val message: String?,
    val stackTrace: String,
    val cause: Cause? = null,
    val suppressedCount: Int = 0,
    val debugInfo: String,
    val extensionsInfo: String? = null,
) {
    @Serializable
    data class Cause(
        val type: String,
        val message: String?,
        val stackTrace: String,
    )

    companion object {
        fun from(
            throwable: Throwable,
            thread: Thread,
            debugInfo: String,
            extensionsInfo: String?,
        ): CrashReport {
            val cause = throwable.cause?.let {
                Cause(
                    type = it::class.java.name,
                    message = it.message,
                    stackTrace = it.stackTraceToString(),
                )
            }
            return CrashReport(
                timestampMs = System.currentTimeMillis(),
                threadName = thread.name,
                type = throwable::class.java.name,
                message = throwable.message,
                stackTrace = throwable.stackTraceToString(),
                cause = cause,
                suppressedCount = throwable.suppressed.size,
                debugInfo = debugInfo,
                extensionsInfo = extensionsInfo,
            )
        }
    }
}
