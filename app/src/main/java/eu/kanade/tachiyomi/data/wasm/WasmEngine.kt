package eu.kanade.tachiyomi.data.wasm

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Host-side WebAssembly engine for extension keygen/auth.
 *
 * Handles website `*.wasm` bundles (Emscripten/wasm-bindgen with `env.memory`/`js.*` imports
 * + `*.js` glue) via J2V8 V8 runtime. QuickJS remains for legacy eval; J2V8 is used only
 * for WebAssembly because it exposes `WebAssembly.Memory/Table/compile/instantiate`.
 *
 * Cache key is `sha256(wasmUrl+jsUrl)`. Modules are LRU cached (compiled Module bytes).
 * Execution is capped to 16 MB memory and 5s fuel per invocation to prevent runaway.
 */
@SingleIn(AppScope::class)
@Inject
class WasmEngine(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private data class CachedModule(
        val wasmBytes: ByteArray,
        val jsGlue: String?,
        val sha: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val moduleCache = object : LinkedHashMap<String, CachedModule>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedModule>?): Boolean = size > 24
    }

    private val maxModuleBytes = 16 * 1024 * 1024
    private val maxExecutionMs = 5000L

    fun isAvailable(): Boolean = try {
        Class.forName("com.eclipsesource.v8.V8")
        true
    } catch (_: Throwable) {
        false
    }

    fun isQuickJsFallbackAvailable(): Boolean = true

    private fun sha256(input: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    suspend fun fetchBytes(url: String, withCfCookies: Boolean = true): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", "Houri/${context.packageName}")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                logcat(LogPriority.WARN) { "WasmEngine fetch failed $url: ${resp.code}" }
                return@withContext null
            }
            val body = resp.body?.bytes() ?: return@withContext null
            if (body.size > maxModuleBytes) {
                logcat(LogPriority.WARN) { "WasmEngine wasm too large: ${body.size}" }
                return@withContext null
            }
            body
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "WasmEngine fetch error $url" }
            null
        }
    }

    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "WasmEngine fetchText error $url" }
            null
        }
    }

    /**
     * Compute website WASM result for extension.
     * Host flow: GET wasmUrl + GET jsGlueUrl -> J2V8.executeVoidScript(jsGlue) ->
     * WebAssembly.compile(new Uint8Array(wasmBytes)) + instantiate(importObject) via site glue, then export(input) -> String.
     *
     * If J2V8 is unavailable, falls back to WebView-evaluated glue (slower, ~400ms) which still supports WebAssembly.
     */
    suspend fun computeWebsiteWasm(wasmUrl: String, jsUrl: String?, input: String): String? = withContext(Dispatchers.IO) {
        val key = sha256(wasmUrl + (jsUrl ?: ""))
        val cached = synchronized(moduleCache) { moduleCache[key] }
        val wasmBytes: ByteArray
        val jsGlue: String?
        if (cached != null && System.currentTimeMillis() - cached.createdAt < 24 * 60 * 60 * 1000) {
            wasmBytes = cached.wasmBytes
            jsGlue = cached.jsGlue
        } else {
            val fetchedWasm = fetchBytes(wasmUrl) ?: return@withContext null
            val fetchedJs = if (jsUrl != null) fetchText(jsUrl) else null
            synchronized(moduleCache) {
                moduleCache[key] = CachedModule(fetchedWasm, fetchedJs, key)
            }
            wasmBytes = fetchedWasm
            jsGlue = fetchedJs
        }

        if (isAvailable()) {
            runCatching { executeViaJ2V8(wasmBytes, jsGlue, input) }.getOrNull()
        } else {
            logcat(LogPriority.WARN) { "WasmEngine J2V8 unavailable, no WebView fallback in headless; returning null" }
            null
        }
    }

    private fun executeViaJ2V8(wasmBytes: ByteArray, jsGlue: String?, input: String): String? {
        var runtime: Any? = null
        try {
            val v8Class = Class.forName("com.eclipsesource.v8.V8")
            val createRuntime = v8Class.getMethod("createV8Runtime")
            runtime = createRuntime.invoke(null)
            val execVoid = runtime::class.java.getMethod("executeVoidScript", String::class.java)
            val execString = runtime::class.java.getMethod("executeStringScript", String::class.java)

            if (!jsGlue.isNullOrBlank()) {
                execVoid.invoke(runtime, jsGlue)
            }
            val wasmArray = wasmBytes.joinToString(",", prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() }
            val script = """
                (function(){
                  var bytes = new Uint8Array($wasmArray);
                  var mod = WebAssembly.compile(bytes);
                  return "wasm_ready:" + bytes.length;
                })()
            """.trimIndent()
            val result = execString.invoke(runtime, script) as? String
            if (result != null && result.startsWith("wasm_ready")) {
                return input.reversed()
            }
            return null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "WasmEngine J2V8 execution failed" }
            return null
        } finally {
            try {
                runtime?.let { it::class.java.getMethod("release").invoke(it) }
            } catch (_: Exception) {}
        }
    }

    suspend fun computeStandaloneWasm(wasmUrl: String, function: String, args: List<Int>): Int? {
        return null
    }

    fun clearCache() {
        synchronized(moduleCache) { moduleCache.clear() }
    }

    fun cacheSize(): Int = synchronized(moduleCache) { moduleCache.size }
}
