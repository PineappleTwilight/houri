package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(value.stackTraceToString())
    }

    @Volatile
    private var lastCrashMs = 0L

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        logcat(priority = LogPriority.ERROR, throwable = exception)
        val now = System.currentTimeMillis()
        val isCrashLoop = now - lastCrashMs < 3000
        lastCrashMs = now
        if (!isCrashLoop) {
            try {
                val report = CrashReport.from(
                    throwable = exception,
                    thread = thread,
                    debugInfo = runCatching { eu.kanade.tachiyomi.util.CrashLogUtil(applicationContext).getDebugInfo() }.getOrDefault("debugInfo unavailable"),
                    extensionsInfo = runCatching { eu.kanade.tachiyomi.util.CrashLogUtil(applicationContext).let { it.javaClass.getDeclaredMethod("getExtensionsInfo").apply { isAccessible = true }.invoke(it) as? String } }.getOrNull(),
                )
                val file = CrashLogWriter.write(applicationContext, report)
                launchActivityWithReport(applicationContext, activityToBeLaunched, exception, file.absolutePath)
            } catch (_: Exception) {
                launchActivity(applicationContext, activityToBeLaunched, exception)
            }
        }
        try {
            defaultHandler.uncaughtException(thread, exception)
        } catch (_: Exception) {
        }
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    private fun launchActivityWithReport(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
        reportPath: String,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            putExtra(INTENT_EXTRA_REPORT_PATH, reportPath)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"
        const val INTENT_EXTRA_REPORT_PATH = "crash_report_path"

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is GlobalExceptionHandler) return
            val fallback = existing ?: Thread.UncaughtExceptionHandler { _, _ -> }
            val handler = GlobalExceptionHandler(
                applicationContext,
                fallback,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }

        fun getReportPathFromIntent(intent: Intent): String? = intent.getStringExtra(INTENT_EXTRA_REPORT_PATH)
    }
}
