package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import logcat.logcat
import java.io.File
import kotlin.random.Random

/**
 * Rarity tiers of the chapter completion sounds, rolled as 60% common / 30%
 * rare / 10% legendary. Each tier maps to a folder inside the external sounds
 * directory that can hold user-imported replacements.
 */
enum class SoundTier(val folderName: String) {
    COMMON("common"),
    RARE("rare"),
    LEGENDARY("legendary"),
}

val SupportedSoundExtensions = setOf("mp3", "wav", "ogg", "oga", "m4a", "flac")

/**
 * Plays a randomized sound when a chapter is completed while reading.
 *
 * Each tier uses bundled assets by default and can be replaced with custom
 * audio by placing files in:
 *
 *   <filesDir>/chapter_complete_sounds/<tier>/
 *
 * where <tier> is the [SoundTier] folder name. Any files found in a tier
 * folder fully replace that tier's bundled sounds, so packs can be extended or
 * customized without code changes. Call [reload] after modifying folder contents.
 */
class ChapterCompleteSoundPlayer(
    private val context: Context,
    private val readerPreferences: ReaderPreferences,
) {

    private data class SoundSource(val key: String, val resId: Int? = null, val path: String? = null)

    private var soundPool: SoundPool? = null
    private var isLoaded = false

    /** Loaded SoundPool sample ids keyed by their source descriptor. */
    private val soundIds = mutableMapOf<SoundSource, Int>()

    private val sourcesByRarity = mutableMapOf<SoundTier, List<SoundSource>>()

    private val builtinSounds = mapOf(
        SoundTier.COMMON to listOf(
            R.raw.moan_common_1,
            R.raw.moan_common_2,
            R.raw.moan_common_3,
        ),
        SoundTier.RARE to listOf(
            R.raw.moan_rare_1,
            R.raw.moan_rare_2,
        ),
        SoundTier.LEGENDARY to listOf(
            R.raw.moan_legendary_1,
        ),
    )

    init {
        initializeSoundPool()
    }

    private fun initializeSoundPool() {
        // Media stream instead of notifications so Do Not Disturb and the
        // notification volume don't silence the effect while reading.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        isLoaded = true
                    }
                }
            }

        loadSounds()
    }

    private fun loadSounds() {
        val pool = soundPool ?: return
        soundIds.clear()
        sourcesByRarity.clear()

        SoundTier.entries.forEach { rarity ->
            val sources = resolveSources(rarity)
            sourcesByRarity[rarity] = sources
            sources.forEach { source ->
                try {
                    val soundId = source.path?.let { pool.load(it, 1) }
                        ?: pool.load(context, source.resId!!, 1)
                    if (soundId != 0) {
                        soundIds[source] = soundId
                    } else {
                        logcat(LogPriority.ERROR) { "Failed to load sound: ${source.key}" }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to load sound: ${source.key}" }
                }
            }
        }
    }

    private fun resolveSources(rarity: SoundTier): List<SoundSource> {
        scanExternalSounds(rarity).takeIf { it.isNotEmpty() }?.let { return it }
        return builtinSounds[rarity].orEmpty().map { resId ->
            SoundSource(key = "res:$resId", resId = resId)
        }
    }

    private fun scanExternalSounds(rarity: SoundTier): List<SoundSource> {
        val tierDir = File(File(context.filesDir, EXTERNAL_SOUNDS_DIR), rarity.folderName)
        return tierDir.listFiles { file -> file.isFile && file.extension.lowercase() in SupportedSoundExtensions }
            ?.sortedBy { it.name.lowercase() }
            ?.map { SoundSource(key = it.absolutePath, path = it.absolutePath) }
            ?: emptyList()
    }

    fun playChapterCompleteSound() {
        if (!readerPreferences.chapterCompletionSound().get()) {
            return
        }

        val pool = soundPool ?: return
        if (!isLoaded) {
            return
        }

        val roll = Random.nextInt(100)
        val targetRarity = when {
            roll < 60 -> SoundTier.COMMON
            roll < 90 -> SoundTier.RARE
            else -> SoundTier.LEGENDARY
        }

        val fallbackOrder = listOf(targetRarity, SoundTier.COMMON, SoundTier.RARE, SoundTier.LEGENDARY).distinct()
        for (rarity in fallbackOrder) {
            val loadedIds = sourcesByRarity[rarity].orEmpty().mapNotNull { soundIds[it] }
            if (loadedIds.isNotEmpty()) {
                val volume = Random.nextFloat() * 0.3f + 0.7f
                pool.play(loadedIds.random(), volume, volume, 1, 0, 1.0f)
                return
            }
        }
    }

    /**
     * Rescans the external sound folders and reloads all sounds. Call after
     * adding, removing or replacing files under [EXTERNAL_SOUNDS_DIR].
     */
    fun reload() {
        val pool = soundPool ?: run {
            initializeSoundPool()
            return
        }
        soundIds.values.forEach { id ->
            runCatching { pool.unload(id) }
        }
        isLoaded = false
        loadSounds()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
        soundIds.clear()
        sourcesByRarity.clear()
    }

    companion object {
        const val EXTERNAL_SOUNDS_DIR = "chapter_complete_sounds"
    }
}
