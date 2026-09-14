package exh.yakuyomi

// KMK --> fake-OkHttp auth tests: login/signup store accessToken, 401→refresh→retry-once, 429→friendlyError
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.atomic.AtomicInteger

private class MapPreferenceStore : PreferenceStore {
    private val strings = mutableMapOf<String, String>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val longs = mutableMapOf<String, Long>()
    private val floats = mutableMapOf<String, Float>()
    private val sets = mutableMapOf<String, Set<String>>()

    private inner class StringPref(private val k: String, private val d: String) : Preference<String> {
        override fun key() = k
        override fun get() = strings[k] ?: d
        override fun set(value: String) {
            strings[k] = value
        }
        override fun isSet() = strings.containsKey(k)
        override fun delete() {
            strings.remove(k)
        }
        override fun defaultValue() = d
        override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
        override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
            kotlinx.coroutines.flow.MutableStateFlow(get())
    }

    override fun getString(key: String, defaultValue: String): Preference<String> =
        StringPref(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        object : Preference<Long> {
            override fun key() = key
            override fun get() = longs[key] ?: defaultValue
            override fun set(value: Long) {
                longs[key] = value
            }
            override fun isSet() = longs.containsKey(key)
            override fun delete() {
                longs.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        object : Preference<Int> {
            override fun key() = key
            override fun get() = ints[key] ?: defaultValue
            override fun set(value: Int) {
                ints[key] = value
            }
            override fun isSet() = ints.containsKey(key)
            override fun delete() {
                ints.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        object : Preference<Float> {
            override fun key() = key
            override fun get() = floats[key] ?: defaultValue
            override fun set(value: Float) {
                floats[key] = value
            }
            override fun isSet() = floats.containsKey(key)
            override fun delete() {
                floats.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        object : Preference<Boolean> {
            override fun key() = key
            override fun get() = booleans[key] ?: defaultValue
            override fun set(value: Boolean) {
                booleans[key] = value
            }
            override fun isSet() = booleans.containsKey(key)
            override fun delete() {
                booleans.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        object : Preference<Set<String>> {
            override fun key() = key
            override fun get() = sets[key] ?: defaultValue
            override fun set(value: Set<String>) {
                sets[key] = value
            }
            override fun isSet() = sets.containsKey(key)
            override fun delete() {
                sets.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        val backing = getString(key, "")
        return object : Preference<T> {
            override fun key() = key
            override fun get(): T {
                val raw = backing.get()
                return if (raw.isEmpty()) defaultValue else deserializer(raw)
            }
            override fun set(value: T) {
                backing.set(serializer(value))
            }
            override fun isSet() = backing.isSet()
            override fun delete() = backing.delete()
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> {
        val backing = getInt(key, 0)
        return object : Preference<T> {
            override fun key() = key
            override fun get(): T {
                return if (!backing.isSet()) defaultValue else deserializer(backing.get())
            }
            override fun set(value: T) {
                backing.set(serializer(value))
            }
            override fun isSet() = backing.isSet()
            override fun delete() = backing.delete()
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }
    }

    override fun getAll(): Map<String, *> = strings.toMap()
}

private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
        .build()

private fun Request.jsonResponse(code: Int, json: String): Response =
    Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(json.toResponseBody("application/json".toMediaType()))
        .build()

private fun relaxedContext(): Context {
    val ctx = mockk<Context>()
    every { ctx.getSystemService(any()) } returns null
    return ctx
}

class MangaTranslatorServiceTest {

    @Test
    fun `login 200 stores accessToken`() = runTest {
        val store = MapPreferenceStore()
        val prefs = TranslationPreferences(store)
        val client = fakeClient { req ->
            when (req.url.encodedPath) {
                "/auth/login" -> req.jsonResponse(
                    200,
                    """{"tokens":{"accessToken":"login-access-token-0123456789","refreshToken":"login-refresh-abcdef"}}""",
                )
                else -> req.jsonResponse(404, "{}")
            }
        }
        val service = MangaTranslatorService(relaxedContext(), prefs, client)

        val result = service.login("user@example.com", "password123")

        assertEquals(LoginResult.Success, result)
        assertEquals("login-access-token-0123456789", prefs.mangaTranslatorAccessToken().get())
        assertEquals("login-refresh-abcdef", prefs.mangaTranslatorRefreshToken().get())
        assertEquals("user@example.com", prefs.mangaTranslatorEmail().get())
    }

    @Test
    fun `signup 200 stores accessToken`() = runTest {
        val store = MapPreferenceStore()
        val prefs = TranslationPreferences(store)
        val client = fakeClient { req ->
            when (req.url.encodedPath) {
                "/signup" -> req.jsonResponse(
                    200,
                    """{"tokens":{"accessToken":"signup-access-token-0123456789","refreshToken":"signup-refresh-abcdef"}}""",
                )
                else -> req.jsonResponse(404, "{}")
            }
        }
        val service = MangaTranslatorService(relaxedContext(), prefs, client)

        val result = service.signup("new@example.com", "password123")

        assertEquals(SignupResult.Success, result)
        assertEquals("signup-access-token-0123456789", prefs.mangaTranslatorAccessToken().get())
        assertEquals("signup-refresh-abcdef", prefs.mangaTranslatorRefreshToken().get())
        assertEquals("new@example.com", prefs.mangaTranslatorEmail().get())
    }

    @Test
    fun `401 refreshes token and retries once`() = runTest {
        val store = MapPreferenceStore()
        val prefs = TranslationPreferences(store)
        prefs.mangaTranslatorAccessToken().set("stale-access-token-aaaaaaaa")
        prefs.mangaTranslatorRefreshToken().set("valid-refresh-token-bbbbb")
        prefs.mangaTranslatorFingerprint().set("test-fingerprint-12345678")
        prefs.mangaTranslatorClientUuid().set("test-client-uuid-12345678901234567890")
        val metricsCalls = AtomicInteger(0)
        val refreshCalls = AtomicInteger(0)
        val client = fakeClient { req ->
            when (req.url.encodedPath) {
                "/metrics" -> {
                    metricsCalls.incrementAndGet()
                    when (req.header("Authorization")) {
                        "Bearer fresh-access-token-cccccccc" ->
                            req.jsonResponse(200, """{"email":"user@example.com","subscriptionTier":"pro"}""")
                        else -> req.jsonResponse(401, """{"error":"unauthorized"}""")
                    }
                }
                "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    req.jsonResponse(
                        200,
                        """{"tokens":{"accessToken":"fresh-access-token-cccccccc","refreshToken":"fresh-refresh-dddddddd"}}""",
                    )
                }
                else -> req.jsonResponse(404, "{}")
            }
        }
        val service = MangaTranslatorService(relaxedContext(), prefs, client)

        val user = service.getCurrentUser()

        assertNotNull(user)
        assertEquals("pro", user!!.subscriptionTier)
        assertEquals("fresh-access-token-cccccccc", prefs.mangaTranslatorAccessToken().get())
        assertEquals(1, refreshCalls.get())
        assertEquals(2, metricsCalls.get())
    }

    @Test
    fun `429 maps to friendly rate-limit error`() {
        val msg = TranslationErrorMapper.toUserMessage(
            "MangaTranslator rate-limited (429) — try again later or log in at ichigo.moe",
        )
        assertTrue(msg.contains("429") || msg.lowercase().contains("rate-limit"))
    }
}
// KMK <--
