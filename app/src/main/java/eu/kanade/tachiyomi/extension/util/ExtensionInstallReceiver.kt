package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Broadcast receiver that listens for the system's packages installed, updated or removed, and only
 * notifies the given [listener] when the package is an extension.
 *
 * @param listener The listener that should be notified of extension installation events.
 */
internal class ExtensionInstallReceiver(private val listener: Listener) : BroadcastReceiver() {

    val scope = CoroutineScope(SupervisorJob())

    fun register(context: Context) {
        logcat(LogPriority.INFO) { "[ExtInstall] ExtensionInstallReceiver registered" }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(ACTION_EXTENSION_ADDED)
        addAction(ACTION_EXTENSION_REPLACED)
        addAction(ACTION_EXTENSION_REMOVED)
        addDataScheme("package")
    }

    /**
     * Called when one of the events of the [filter] is received. When the package is an extension,
     * it's loaded in background and it notifies the [listener] when finished.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pkgName = getPackageNameFromIntent(intent)
        if (pkgName == null) {
            logcat(LogPriority.WARN) { "[ExtInstall] ExtensionInstallReceiver: no package name in intent" }
            return
        }

        // KMK -->
        // Short-circuit: skip packages that are definitely not extensions
        // to avoid full loadExtensionFromPkgName on every app install
        val pkgInfo = try {
            context.packageManager.getPackageInfo(pkgName, 0)
        } catch (_: Exception) {
            null
        }
        val isExtension = pkgInfo?.reqFeatures.orEmpty().any { it.name == "tachiyomi.extension" }
        if (!isExtension && intent.action != ACTION_EXTENSION_ADDED &&
            intent.action != ACTION_EXTENSION_REPLACED &&
            intent.action != ACTION_EXTENSION_REMOVED
        ) {
            return
        }
        // KMK <--

        logcat(LogPriority.INFO) { "[ExtInstall] ExtensionInstallReceiver action=${intent.action} pkg=$pkgName" }

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED, ACTION_EXTENSION_ADDED -> {
                if (isReplacing(intent)) {
                    logcat(LogPriority.INFO) { "[ExtInstall] Skipping ADD (replacing): $pkgName" }
                    return
                }

                scope.launch {
                    val result = getExtensionFromIntent(context, intent)
                    logcat(LogPriority.INFO) { "[ExtInstall] loadResult for $pkgName: $result" }
                    when (result) {
                        is LoadResult.Success -> listener.onExtensionInstalled(result.extension)
                        is LoadResult.Untrusted -> listener.onExtensionUntrusted(result.extension)
                        // KMK -->
                        is LoadResult.Error -> {
                            logcat(LogPriority.WARN) { "[ExtInstall] Extension $pkgName failed to load after install — check earlier logs for details" }
                        }
                        null -> {
                            logcat(LogPriority.WARN) { "[ExtInstall] Extension $pkgName load returned null — package may not be a valid extension" }
                        }
                        // KMK <--
                    }
                }
            }
            Intent.ACTION_PACKAGE_REPLACED, ACTION_EXTENSION_REPLACED -> {
                logcat(LogPriority.INFO) { "[ExtInstall] Package replaced: $pkgName" }
                scope.launch {
                    when (val result = getExtensionFromIntent(context, intent)) {
                        is LoadResult.Success -> listener.onExtensionUpdated(result.extension)
                        is LoadResult.Untrusted -> listener.onExtensionUntrusted(result.extension)
                        // KMK -->
                        is LoadResult.Error -> {
                            logcat(LogPriority.WARN) { "[ExtInstall] Extension $pkgName failed to load after update — check earlier logs for details" }
                        }
                        null -> {
                            logcat(LogPriority.WARN) { "[ExtInstall] Extension $pkgName load returned null after update" }
                        }
                        // KMK <--
                    }
                }
            }
            Intent.ACTION_PACKAGE_REMOVED, ACTION_EXTENSION_REMOVED -> {
                if (isReplacing(intent)) return

                val pkgName = getPackageNameFromIntent(intent)
                if (pkgName != null) {
                    listener.onPackageUninstalled(pkgName)
                }
            }
        }
    }

    /**
     * Returns true if this package is performing an update.
     *
     * @param intent The intent that triggered the event.
     */
    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    /**
     * Returns the extension triggered by the given intent.
     *
     * @param context The application context.
     * @param intent The intent containing the package name of the extension.
     */
    private suspend fun getExtensionFromIntent(context: Context, intent: Intent?): LoadResult {
        val pkgName = getPackageNameFromIntent(intent)
        if (pkgName == null) {
            logcat(LogPriority.WARN) { "Package name not found" }
            return LoadResult.Error
        }
        return ExtensionLoader.loadExtensionFromPkgName(context, pkgName)
    }

    /**
     * Returns the package name of the installed, updated or removed application.
     */
    private fun getPackageNameFromIntent(intent: Intent?): String? {
        return intent?.data?.encodedSchemeSpecificPart ?: return null
    }

    /**
     * Listener that receives extension installation events.
     */
    interface Listener {
        fun onExtensionInstalled(extension: Extension.Installed)
        fun onExtensionUpdated(extension: Extension.Installed)
        fun onExtensionUntrusted(extension: Extension.Untrusted)
        fun onPackageUninstalled(pkgName: String)
    }

    companion object {
        private const val ACTION_EXTENSION_ADDED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_ADDED"
        private const val ACTION_EXTENSION_REPLACED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REPLACED"
        private const val ACTION_EXTENSION_REMOVED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REMOVED"

        fun notifyAdded(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_ADDED)
        }

        fun notifyReplaced(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REPLACED)
        }

        fun notifyRemoved(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REMOVED)
        }

        private fun notify(context: Context, pkgName: String, action: String) {
            Intent(action).apply {
                data = "package:$pkgName".toUri()
                `package` = context.packageName
                context.sendBroadcast(this)
            }
        }
    }
}
