package eu.kanade.tachiyomi.ui.manga

import exh.yakuyomi.MangaInfoProviderState
import exh.yakuyomi.MangaInfoTranslationStore
import exh.yakuyomi.TranslationManager
import exh.yakuyomi.resolveMangaInfoSourceLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.PreferenceStore

// KMK --> Manga-details metadata translation lifecycle owner (spec 2026-09-23).

/** User-facing failure category for metadata translation. Resolution to strings happens in UI. */
enum class MangaInfoErrorKind {
    ProviderNotReady,
    MangaTranslatorUnsupported,
    Failed,
}

/** Publishable metadata-translation state for the details screen. */
sealed interface MangaInfoUiState {
    data object Hidden : MangaInfoUiState
    data object Disabled : MangaInfoUiState
    data object Translating : MangaInfoUiState
    data class Translated(
        val title: String,
        val description: String?,
        val showTranslated: Boolean = true,
    ) : MangaInfoUiState
    data class Error(val kind: MangaInfoErrorKind) : MangaInfoUiState
}

/** Identity of one bind's source metadata, with the source language normalized. */
private data class BoundKey(
    val sourceId: Long?,
    val title: String,
    val description: String?,
    val sourceLang: String,
)

/** Immutable per-request metadata snapshot owned by a single launched translation job. */
private data class InfoRequest(
    val sourceId: Long?,
    val title: String,
    val description: String?,
    val sourceLang: String?,
)

/** Whether repeated binds with the same identity must leave this state untouched. */
private val MangaInfoUiState.preservesAcrossRebind: Boolean
    get() = this is MangaInfoUiState.Translating ||
        this is MangaInfoUiState.Translated ||
        this is MangaInfoUiState.Error

/**
 * Owns the per-manga metadata ("translate details") preference and lifecycle.
 *
 * Eligibility is independent of the per-manga page-translation toggle: only the global
 * MTL switch, the incognito/censor gates, and text-provider readiness apply. Late results
 * can never publish: every disable/refresh/reset bumps the generation and cancels the
 * in-flight job, and completions from a superseded generation are dropped.
 */
class MangaInfoTranslationController(
    private val mangaId: Long,
    private val scope: CoroutineScope,
    private val preferenceStore: PreferenceStore,
    private val translationManager: TranslationManager,
    private val infoStore: MangaInfoTranslationStore,
    private val isNoMtl: Boolean,
) {
    private val enabledPref = preferenceStore.getBoolean("pref_translate_info_$mangaId", false)

    private val _state = MutableStateFlow<MangaInfoUiState>(MangaInfoUiState.Disabled)
    val state: StateFlow<MangaInfoUiState> = _state.asStateFlow()

    val isEnabled: Boolean
        get() = enabledPref.get()

    @Volatile
    private var generation = 0
    private var job: Job? = null

    /**
     * Guards [generation] and [job]: bind runs on the details collector dispatcher while
     * user actions originate on the UI dispatcher, so every bump/cancel/track step is
     * confined here. StateFlow itself is already thread-safe.
     */
    private val stateLock = Any()

    private var lastSourceId: Long? = null
    private var lastTitle: String = ""
    private var lastDescription: String? = null
    private var lastSourceLang: String? = null

    /** Identity of the source metadata the current state was bound to, if any. */
    private var lastBoundKey: BoundKey? = null

    /**
     * (Re)binds the controller to the current source metadata. Re-evaluates availability
     * and, when enabled, serves the validated cache or starts a translation.
     *
     * Idempotent for unchanged metadata: while a request is in flight or a result/error
     * is already bound to the same identity, repeated emissions leave the state alone
     * and issue no new request. Hidden/Disabled always re-evaluate so availability and
     * toggle changes take effect on the next emission.
     */
    fun bind(sourceId: Long?, title: String, description: String?, sourceLang: String?) {
        val request = synchronized(stateLock) {
            lastSourceId = sourceId
            lastTitle = title
            lastDescription = description
            lastSourceLang = sourceLang
            InfoRequest(sourceId, title, description, sourceLang)
        }
        // KMK --> Availability re-evaluates before the idempotence guard so a mid-flight
        // gate (no-MTL, global off, incognito/censor) always hides and cancels first.
        if (isNoMtl || !translationManager.isEnabled() || translationManager.isGated()) {
            cancelWork()
            _state.value = MangaInfoUiState.Hidden
            return
        }
        val key = BoundKey(
            request.sourceId,
            request.title,
            request.description,
            resolveMangaInfoSourceLang(request.sourceLang),
        )
        val proceed = synchronized(stateLock) {
            if (key == lastBoundKey && _state.value.preservesAcrossRebind) {
                false
            } else {
                lastBoundKey = key
                true
            }
        }
        if (!proceed) return
        if (!enabledPref.get()) {
            cancelWork()
            _state.value = MangaInfoUiState.Disabled
            return
        }
        if (request.title.isBlank() && request.description.isNullOrBlank()) {
            cancelWork()
            _state.value = MangaInfoUiState.Disabled
            return
        }
        translate(request, force = false)
    }

    fun setEnabled(enabled: Boolean) {
        enabledPref.set(enabled)
        if (!enabled) {
            cancelWork()
            runCatching { infoStore.clear(mangaId) }
            _state.value = MangaInfoUiState.Disabled
        } else {
            val request = synchronized(stateLock) {
                InfoRequest(lastSourceId, lastTitle, lastDescription, lastSourceLang)
            }
            bind(request.sourceId, request.title, request.description, request.sourceLang)
        }
    }

    fun setShowTranslated(show: Boolean) {
        val current = _state.value as? MangaInfoUiState.Translated ?: return
        if (current.showTranslated != show) {
            _state.value = current.copy(showTranslated = show)
        }
    }

    /** Bypasses the cache and retries with the current source metadata and settings. */
    fun refresh() {
        if (_state.value is MangaInfoUiState.Hidden) return
        if (!enabledPref.get()) return
        translate(snapshotRequest(), force = true)
    }

    fun retry() {
        if (_state.value !is MangaInfoUiState.Error) return
        translate(snapshotRequest(), force = true)
    }

    /** Cancels in-flight work, clears the persisted cache, and returns to original metadata. */
    fun reset() {
        setEnabled(false)
    }

    private fun snapshotRequest(): InfoRequest = synchronized(stateLock) {
        InfoRequest(lastSourceId, lastTitle, lastDescription, lastSourceLang)
    }

    private fun translate(request: InfoRequest, force: Boolean) {
        val myGeneration: Int
        synchronized(stateLock) {
            generation += 1
            myGeneration = generation
            job?.cancel()
            job = null
        }
        _state.value = MangaInfoUiState.Translating
        val newJob = scope.launch {
            val cached = if (!force) {
                runCatching {
                    translationManager.getValidCachedMangaInfo(
                        mangaId,
                        request.sourceId,
                        request.title,
                        request.description,
                        request.sourceLang ?: "JA",
                    )
                }.getOrNull()
            } else {
                null
            }
            if (myGeneration != generation) return@launch
            if (cached != null) {
                _state.value = MangaInfoUiState.Translated(
                    title = cached.title,
                    description = cached.description,
                )
                return@launch
            }
            // A readiness lookup failure must not strand the UI in Translating: treat it
            // like any other provider failure (retryable), never publishing a translation.
            val providerState = try {
                translationManager.mangaInfoProviderState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (providerState == null) {
                if (myGeneration == generation) {
                    _state.value = MangaInfoUiState.Error(MangaInfoErrorKind.Failed)
                }
                return@launch
            }
            when (providerState) {
                MangaInfoProviderState.MANGA_TRANSLATOR_UNSUPPORTED -> {
                    if (myGeneration == generation) {
                        _state.value = MangaInfoUiState.Error(MangaInfoErrorKind.MangaTranslatorUnsupported)
                    }
                    return@launch
                }
                MangaInfoProviderState.NOT_CONFIGURED -> {
                    if (myGeneration == generation) {
                        _state.value = MangaInfoUiState.Error(MangaInfoErrorKind.ProviderNotReady)
                    }
                    return@launch
                }
                MangaInfoProviderState.READY -> Unit
            }
            val result = try {
                translationManager.translateMangaInfo(
                    mangaId,
                    request.title,
                    request.description,
                    request.sourceLang ?: "JA",
                    request.sourceId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (myGeneration != generation) return@launch
            if (result == null) {
                _state.value = MangaInfoUiState.Error(MangaInfoErrorKind.Failed)
            } else {
                _state.value = MangaInfoUiState.Translated(
                    title = result.title,
                    description = result.description,
                )
            }
        }
        synchronized(stateLock) {
            // Only the latest request tracks its job; a superseded launch cancels its own
            // job here so no stale job stays active and only the newest can publish.
            if (myGeneration == generation) {
                job = newJob
            } else {
                newJob.cancel()
            }
        }
    }

    /**
     * Invalidates in-flight work: advances the generation so even a non-cooperative job
     * that ignores cancellation fails the publish guard, then cancels and untracks the job.
     */
    private fun cancelWork() {
        synchronized(stateLock) {
            generation += 1
            job?.cancel()
            job = null
        }
    }
}
// KMK <--
