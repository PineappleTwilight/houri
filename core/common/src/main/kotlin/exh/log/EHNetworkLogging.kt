package exh.log

import com.elvishew.xlog.XLog
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

private const val EH_NETWORK_TAG = "||EH-NETWORK-JSON"

/**
 * The EXTREME ("Network inspection mode") body logger as a single shared interceptor. Every
 * client the app builds reuses this one instance, so a client derived from an already
 * instrumented one (e.g. a source client built from the base client via `newBuilder()`)
 * recognizes it and never logs the same request twice.
 */
private val ehNetworkBodyInterceptor: Interceptor by lazy {
    val xlogBorder = XLog.tag(EH_NETWORK_TAG).build()
    val xlogNoBorder = XLog.tag(EH_NETWORK_TAG).disableBorder().build()
    val logger = HttpLoggingInterceptor.Logger { message ->
        try {
            Json.decodeFromString<Any>(message)
            xlogBorder.json(message)
        } catch (ex: Exception) {
            xlogNoBorder.d(message)
        }
    }
    HttpLoggingInterceptor(logger).apply { level = HttpLoggingInterceptor.Level.BODY }
}

fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder {
    // KMK -->
    if (EHLogLevel.isExtremeLogging() && interceptors().none { it === ehNetworkBodyInterceptor }) {
        return addInterceptor(ehNetworkBodyInterceptor)
    }
    // KMK <--
    return this
}

/**
 * EXTRA ("Log everything") request/response headers, routed through XLog so they land in the
 * same log files and logcat bridge as everything else the app logs - HttpLoggingInterceptor's
 * default logger writes to System.out, which neither the log files nor the logcat bridge read.
 * Also a shared singleton, for the same double-injection reason as the body interceptor.
 */
private val extraHeaderInterceptor: Interceptor by lazy {
    val xlog = XLog.tag("OKHTTP").build()
    HttpLoggingInterceptor { message -> runCatching { xlog.i(message) } }.apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }
}

fun OkHttpClient.Builder.maybeInjectExtraHeaderLogger(): OkHttpClient.Builder {
    // KMK -->
    if (EHLogLevel.isExtraLogging() && networkInterceptors().none { it === extraHeaderInterceptor }) {
        return addNetworkInterceptor(extraHeaderInterceptor)
    }
    // KMK <--
    return this
}
