package eu.kanade.tachiyomi.data.wasm

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.OkHttpClient

@SingleIn(AppScope::class)
@Inject
class WasmEngine(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val moduleCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)

    fun isAvailable(): Boolean = try {
        Class.forName("com.eclipsesource.v8.V8")
        true
    } catch (_: Throwable) { false }

    suspend fun computeWebsiteWasm(wasmUrl: String, jsUrl: String?, input: String): String? {
        // Stub: real implementation loads wasm bytes + js glue via J2V8.
        // Kept as scaffold for Houri WASM extension ABI.
        return null
    }

    fun clearCache() { moduleCache.clear() }
}
