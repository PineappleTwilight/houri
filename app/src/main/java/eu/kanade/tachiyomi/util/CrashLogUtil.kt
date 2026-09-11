package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import java.time.Instant

@Inject
@SingleIn(AppScope::class)
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager = globalAppGraph.extensionManager,
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val report = eu.kanade.tachiyomi.crash.CrashReport.from(
                throwable = exception ?: Throwable("Manual dump"),
                thread = Thread.currentThread(),
                debugInfo = getDebugInfo(),
                extensionsInfo = getExtensionsInfo(),
            )
            val file = eu.kanade.tachiyomi.crash.CrashLogWriter.write(context, report)
            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = activityManager?.let {
            val mi = android.app.ActivityManager.MemoryInfo()
            it.getMemoryInfo(mi)
            "RAM: ${mi.availMem / 1024 / 1024}MB avail / ${mi.totalMem / 1024 / 1024}MB total (low=${mi.lowMemory}, threshold=${mi.threshold / 1024 / 1024}MB)"
        } ?: "RAM: unknown"
        val heapInfo = try {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            val max = rt.maxMemory() / 1024 / 1024
            "Heap: ${used}MB used / ${max}MB max (free=${rt.freeMemory() / 1024 / 1024}MB)"
        } catch (_: Exception) {
            "Heap: unknown"
        }
        val storageInfo = try {
            val free = context.cacheDir.freeSpace / 1024 / 1024
            val total = context.cacheDir.totalSpace / 1024 / 1024
            "Storage: ${free}MB free / ${total}MB total (cacheDir)"
        } catch (_: Exception) {
            "Storage: unknown"
        }
        val batteryInfo = try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) "Battery: $level%" else "Battery: unknown"
        } catch (_: Exception) {
            "Battery: unknown"
        }
        val localeInfo = try {
            "Locale: ${java.util.Locale.getDefault()}"
        } catch (_: Exception) {
            "Locale: unknown"
        }
        val orientationInfo = try {
            "Orientation: ${context.resources.configuration.orientation}"
        } catch (_: Exception) {
            "Orientation: unknown"
        }
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Build version: ${BuildConfig.COMMIT_COUNT}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${Instant.now()}
            $memInfo
            $heapInfo
            $storageInfo
            $batteryInfo
            $localeInfo
            $orientationInfo
            Process: ${android.os.Process.myPid()} / Thread: ${Thread.currentThread().name}
        """.trimIndent()
    }

    internal fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.installedExtensionsFlow.value
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }
}
