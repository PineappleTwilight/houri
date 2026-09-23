package eu.kanade.tachiyomi.ui.manga

// KMK --> State-transition, cancellation, and independent-gating coverage for metadata translation.
import exh.yakuyomi.MangaInfoProviderState
import exh.yakuyomi.MangaInfoTranslation
import exh.yakuyomi.MangaInfoTranslationStore
import exh.yakuyomi.TranslateMangaStore
import exh.yakuyomi.TranslationManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

private class MapPreferenceStore : PreferenceStore {
    private val booleans = mutableMapOf<String, Boolean>()
    private val strings = mutableMapOf<String, String>()

    override fun getString(key: String, defaultValue: String): Preference<String> =
        object : Preference<String> {
            override fun key() = key
            override fun get() = strings[key] ?: defaultValue
            override fun set(value: String) {
                strings[key] = value
            }
            override fun isSet() = strings.containsKey(key)
            override fun delete() {
                strings.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun stateIn(scope: kotlinx.coroutines.CoroutineScope) =
                kotlinx.coroutines.flow.MutableStateFlow(get())
        }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = TODO()
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = TODO()
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TODO()

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

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = TODO()
    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = TODO()
    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (Int) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = TODO()
    override fun getAll(): Map<String, *> = booleans.toMap()
}

@Execution(ExecutionMode.CONCURRENT)
class MangaInfoTranslationControllerTest {

    private data class Harness(
        val controller: MangaInfoTranslationController,
        val manager: TranslationManager,
        val infoStore: MangaInfoTranslationStore,
        val prefs: MapPreferenceStore,
    )

    private fun harness(
        scope: kotlinx.coroutines.test.TestScope,
        isNoMtl: Boolean = false,
    ): Harness {
        val prefs = MapPreferenceStore()
        val manager = mockk<TranslationManager>()
        val infoStore = mockk<MangaInfoTranslationStore>()
        every { manager.isEnabled() } returns true
        every { manager.isGated() } returns false
        every { manager.mangaInfoProviderState() } returns MangaInfoProviderState.READY
        every { manager.getValidCachedMangaInfo(any(), any(), any(), any(), any()) } returns null
        every { infoStore.clear(any()) } returns Unit
        return Harness(
            MangaInfoTranslationController(1L, scope, prefs, manager, infoStore, isNoMtl),
            manager,
            infoStore,
            prefs,
        )
    }

    private fun enableInfoPref(prefs: MapPreferenceStore) {
        prefs.getBoolean("pref_translate_info_1", false).set(true)
    }

    @Test
    fun `bind emits Hidden in no-MTL builds without translating`() = runTest {
        val h = harness(this, isNoMtl = true)
        enableInfoPref(h.prefs)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Hidden
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.manager.getValidCachedMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bind emits Hidden when global MTL is off`() = runTest {
        val h = harness(this)
        every { h.manager.isEnabled() } returns false
        enableInfoPref(h.prefs)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Hidden
    }

    @Test
    fun `bind emits Hidden when incognito or censor gates apply`() = runTest {
        val h = harness(this)
        every { h.manager.isGated() } returns true
        enableInfoPref(h.prefs)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Hidden
    }

    @Test
    fun `bind emits Disabled when the per-manga toggle is off`() = runTest {
        val h = harness(this)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Disabled
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bind serves validated cache without translating`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        every { h.manager.getValidCachedMangaInfo(1L, 7L, "Title", "Desc", "JA") } returns
            MangaInfoTranslation(title = "Cached Title", description = "Cached desc")

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Translated("Cached Title", "Cached desc", true)
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `metadata translates while the page-translation toggle stays off`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val pageStore = TranslateMangaStore(h.prefs)
        pageStore.isEnabled(1L) shouldBe false
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n Title", description = null)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        pageStore.isEnabled(1L) shouldBe false
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n Title", null, true)
    }

    @Test
    fun `unconfigured provider surfaces Error without calling translate`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        every { h.manager.mangaInfoProviderState() } returns MangaInfoProviderState.NOT_CONFIGURED

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Error(MangaInfoErrorKind.ProviderNotReady)
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mangatranslator selection surfaces unsupported Error`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        every { h.manager.mangaInfoProviderState() } returns MangaInfoProviderState.MANGA_TRANSLATOR_UNSUPPORTED

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Error(MangaInfoErrorKind.MangaTranslatorUnsupported)
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed translation surfaces Error and retry re-attempts`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        var calls = 0
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers {
            calls++
            if (calls == 1) null else MangaInfoTranslation(title = "Recovered", description = null)
        }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Error(MangaInfoErrorKind.Failed)

        h.controller.retry()
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("Recovered", null, true)
        calls shouldBe 2
    }

    @Test
    fun `refresh bypasses cache and drops the superseded result`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val staleGate = CompletableDeferred<MangaInfoTranslation?>()
        var calls = 0
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers {
            calls++
            if (calls == 1) staleGate.await() else MangaInfoTranslation(title = "Fresh", description = null)
        }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translating

        h.controller.refresh()
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("Fresh", null, true)

        staleGate.complete(MangaInfoTranslation(title = "Late", description = null))
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("Fresh", null, true)
        calls shouldBe 2
    }

    @Test
    fun `disable clears cache and drops late results`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val gate = CompletableDeferred<MangaInfoTranslation?>()
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers { gate.await() }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translating

        h.controller.setEnabled(false)
        h.controller.state.value shouldBe MangaInfoUiState.Disabled
        verify { h.infoStore.clear(1L) }

        gate.complete(MangaInfoTranslation(title = "Late", description = null))
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Disabled
    }

    @Test
    fun `show-original toggle switches display without deleting cache`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = "T9n desc")

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.setShowTranslated(false)
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", "T9n desc", false)
        h.controller.setShowTranslated(true)
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", "T9n desc", true)
        verify(exactly = 0) { h.infoStore.clear(any()) }
    }

    @Test
    fun `reset clears cache and returns to Disabled`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = null)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)

        h.controller.reset()
        h.controller.state.value shouldBe MangaInfoUiState.Disabled
        h.controller.isEnabled shouldBe false
        verify { h.infoStore.clear(1L) }
    }

    @Test
    fun `repeated bind with unchanged metadata issues no new request`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = null)

        repeat(3) {
            h.controller.bind(7L, "Title", "Desc", "JA")
            testScheduler.advanceUntilIdle()
        }

        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
        verify(exactly = 1) { h.manager.getValidCachedMangaInfo(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `repeated bind preserves in-flight work without restarting`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val gate = CompletableDeferred<MangaInfoTranslation?>()
        var calls = 0
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers {
            calls++
            gate.await()
        }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Translating
        calls shouldBe 1

        gate.complete(MangaInfoTranslation(title = "T9n", description = null))
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
    }

    @Test
    fun `bind with changed metadata rebinds`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = null)
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc2", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n2", description = null)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)

        h.controller.bind(7L, "Title", "Desc2", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n2", null, true)

        coVerify(exactly = 1) { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) }
        coVerify(exactly = 1) { h.manager.translateMangaInfo(1L, "Title", "Desc2", "JA", 7L) }
    }

    @Test
    fun `bind re-evaluates availability after Hidden`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        every { h.manager.isEnabled() } returns false
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = null)

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Hidden
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }

        every { h.manager.isEnabled() } returns true
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
        coVerify(exactly = 1) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `language case-only change does not rebind`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        coEvery { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) } returns
            MangaInfoTranslation(title = "T9n", description = null)

        h.controller.bind(7L, "Title", "Desc", "ja")
        testScheduler.advanceUntilIdle()
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
        coVerify(exactly = 1) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `gating mid-flight publishes Hidden and recovers on the same metadata`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val gate = CompletableDeferred<MangaInfoTranslation?>()
        var calls = 0
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers {
            calls++
            if (calls == 1) gate.await() else MangaInfoTranslation(title = "T9n", description = null)
        }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translating

        every { h.manager.isGated() } returns true
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Hidden

        gate.complete(MangaInfoTranslation(title = "Late", description = null))
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Hidden
        calls shouldBe 1

        every { h.manager.isGated() } returns false
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
        calls shouldBe 2
    }

    @Test
    fun `non-cooperative late result cannot publish after bind invalidates`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        val gate = CompletableDeferred<MangaInfoTranslation?>()
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            MangaInfoTranslation(title = "Late", description = null)
        }

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translating

        every { h.manager.isGated() } returns true
        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Hidden

        gate.complete(null)
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Hidden
        coVerify(exactly = 1) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `provider-state lookup failure surfaces retryable Error`() = runTest {
        val h = harness(this)
        enableInfoPref(h.prefs)
        every { h.manager.mangaInfoProviderState() } throws RuntimeException("readiness boom")

        h.controller.bind(7L, "Title", "Desc", "JA")
        testScheduler.advanceUntilIdle()

        h.controller.state.value shouldBe MangaInfoUiState.Error(MangaInfoErrorKind.Failed)
        coVerify(exactly = 0) { h.manager.translateMangaInfo(any(), any(), any(), any(), any()) }

        every { h.manager.mangaInfoProviderState() } returns MangaInfoProviderState.READY
        coEvery { h.manager.translateMangaInfo(1L, "Title", "Desc", "JA", 7L) } returns
            MangaInfoTranslation(title = "T9n", description = null)
        h.controller.retry()
        testScheduler.advanceUntilIdle()
        h.controller.state.value shouldBe MangaInfoUiState.Translated("T9n", null, true)
    }
}
// KMK <--
