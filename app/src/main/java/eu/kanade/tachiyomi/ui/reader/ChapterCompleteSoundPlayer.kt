package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import logcat.logcat
import kotlin.random.Random

class ChapterCompleteSoundPlayer(
    private val context: Context,
    private val readerPreferences: ReaderPreferences,
) {

    private var soundPool: SoundPool? = null
    private var isLoaded = false

    private val soundIds = mutableMapOf<Int, Int>()

    private val commonSounds = listOf(
        R.raw.moan_common_1,
        R.raw.moan_common_2,
        R.raw.moan_common_3,
    )
    private val rareSounds = listOf(
        R.raw.moan_rare_1,
        R.raw.moan_rare_2,
    )
    private val legendarySounds = listOf(
        R.raw.moan_legendary_1,
    )

    init {
        initializeSoundPool()
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
        val allSounds = commonSounds + rareSounds + legendarySounds
        if (allSounds.isEmpty()) return

        allSounds.forEach { resId ->
            try {
                val soundId = pool.load(context, resId, 1)
                soundIds[resId] = soundId
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to load sound: $resId" }
            }
        }
    }

    fun playChapterCompleteSound() {
        if (!readerPreferences.chapterCompletionSound().get()) {
            return
        }

        val pool = soundPool ?: return
        if (!isLoaded || commonSounds.isEmpty()) {
            return
        }

        val selectedResId = selectRandomSound() ?: return
        val soundId = soundIds[selectedResId] ?: return

        val volume = Random.nextFloat() * 0.3f + 0.7f
        pool.play(soundId, volume, volume, 1, 0, 1.0f)
    }

    private fun selectRandomSound(): Int? {
        if (commonSounds.isEmpty()) return null
        val roll = Random.nextInt(100)
        return when {
            roll < 60 -> commonSounds.random()
            roll < 90 && rareSounds.isNotEmpty() -> rareSounds.random()
            legendarySounds.isNotEmpty() -> legendarySounds.random()
            else -> commonSounds.random()
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
        soundIds.clear()
    }
}
