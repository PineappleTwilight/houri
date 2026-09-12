package eu.kanade.tachiyomi.data.track.interceptor

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shared cookie-jar logic for scraping-based trackers (AnimePlanet, ComicK, ...).
 *
 * Hardened vs the previous duplicated interceptors:
 * - Thread-safe via [ReentrantLock] (was unsynchronized mutableMap)
 * - Proper expiry filtering (was missing in ComicK)
 * - Subdomain-aware matching (was missing in AnimePlanet)
 * - Handles both `host` vs `domain` cookie variants
 * - Defensive parsing of cookie header (trims, skips empty, limits splits)
 * - Single persistence path via lambdas (tracker-specific pref key)
 */
abstract class AbstractCookieTrackerInterceptor(
    private val baseUrl: HttpUrl,
    private val getPersistedHeader: () -> String?,
    private val persistHeader: (String) -> Unit,
) : Interceptor {

    private val lock = ReentrantLock()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJarImpl = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            lock.withLock {
                val existing = cookieStore[url.host].orEmpty().toMutableList()
                val newNames = cookies.map { it.name }.toSet()
                val filtered = existing.filter { it.name !in newNames }.toMutableList()
                filtered.addAll(cookies)
                cookieStore[url.host] = filtered
                persistCookiesLocked()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            lock.withLock {
                return cookieStore.values
                    .flatten()
                    .filter { !it.hasExpired() }
                    .filter { cookie ->
                        // Exact host match OR subdomain match for api.* vs bare domain.
                        url.host == cookie.domain ||
                            url.host.endsWith(".${cookie.domain}") ||
                            cookie.domain == url.host ||
                            // Cookie built with baseUrl.host but request is subdomain
                            cookie.domain == baseUrl.host && url.host.endsWith(".${baseUrl.host}")
                    }
            }
        }
    }

    fun getCookieJar(): CookieJar = cookieJarImpl

    fun restoreFromCookieHeader(cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        // Defensive: limit header length to avoid OOM on corrupt prefs (8KB cap)
        val header = cookieHeader.take(8192)
        val cookies = header.split(";")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty() || !trimmed.contains("=")) return@mapNotNull null
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                // Cookie names are validated by OkHttp, but guard empty value
                try {
                    Cookie.Builder()
                        .domain(baseUrl.host)
                        .path("/")
                        .name(name)
                        .value(value)
                        .build()
                } catch (_: Exception) {
                    null
                }
            }
        if (cookies.isNotEmpty()) {
            lock.withLock {
                cookieStore[baseUrl.host] = cookies.toMutableList()
            }
        }
    }

    /**
     * Clears existing cookies and seeds from [cookieHeader] (full header or single value).
     * Subclasses may override to wrap bare values (e.g. ComicK's ory_kratos_session).
     */
    open fun newAuth(cookieHeader: String?) {
        lock.withLock { cookieStore.clear() }
        if (!cookieHeader.isNullOrBlank()) {
            val normalized = normalizeCookieHeader(cookieHeader)
            restoreFromCookieHeader(normalized)
            lock.withLock { persistCookiesLocked() }
        } else {
            lock.withLock { persistCookiesLocked() }
        }
    }

    protected open fun normalizeCookieHeader(raw: String): String = raw.trim()

    private fun persistCookiesLocked() {
        // Called with lock held in most paths; also safe unlocked
        val header = cookieStore.values.flatten()
            .filter { !it.hasExpired() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        try {
            persistHeader(header)
        } catch (_: Exception) {
            // Persistence must not crash request thread
        }
    }

    private fun Cookie.hasExpired(): Boolean {
        val expiry = expiresAt
        return expiry != Long.MIN_VALUE && expiry != 0L && System.currentTimeMillis() > expiry
    }

    /**
     * Subclasses must implement how to build the authenticated request.
     * Common validation: ensure at least one non-expired cookie exists, otherwise throw.
     */
    protected fun requireCookiesOrThrow(): List<Cookie> {
        val cookies = cookieJarImpl.loadForRequest(baseUrl)
        if (cookies.isEmpty()) {
            throw IOException("Not authenticated with tracker at ${baseUrl.host}")
        }
        return cookies
    }

    /**
     * Builds a cookie header string for outgoing requests.
     */
    protected fun cookieHeader(): String {
        val cookies = cookieJarImpl.loadForRequest(baseUrl)
        // Re-check after load
        if (cookies.isEmpty()) throw IOException("Not authenticated with tracker at ${baseUrl.host}")
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Default implementation: add Cookie header; subclasses add UA/Referer etc.
        val cookieHeader = cookieHeader()
        val request = chain.request().newBuilder()
            .header("Cookie", cookieHeader)
            .build()
        return chain.proceed(request)
    }

    init {
        // Restore persisted cookies on construction — best effort, never throw
        try {
            val persisted = getPersistedHeader()
            if (!persisted.isNullOrBlank()) {
                restoreFromCookieHeader(persisted)
            }
        } catch (_: Exception) {
        }
    }
}
