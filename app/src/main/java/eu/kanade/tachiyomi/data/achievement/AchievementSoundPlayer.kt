package eu.kanade.tachiyomi.data.achievement

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.model.AchievementTier
import tachiyomi.domain.achievement.service.AchievementPreferences
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@SingleIn(AppScope::class)
@Inject
class AchievementSoundPlayer(
    private val context: Context,
    private val prefs: AchievementPreferences,
) {
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<AchievementTier, Int>()
    private val cacheDir by lazy { File(context.cacheDir, "achievement_chimes").apply { mkdirs() } }

    init {
        init()
    }

    private fun init() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
            ensureChimesExist()
            loadAll()
        } catch (_: Exception) {}
    }

    private fun ensureChimesExist() {
        for (tier in AchievementTier.entries) {
            val out = File(cacheDir, "${tier.name.lowercase()}.wav")
            if (out.exists() && out.length() > 1000) continue
            try {
                val wav = synthesizeChime(tier)
                out.writeBytes(wav)
            } catch (_: Exception) {}
        }
    }

    private fun loadAll() {
        val pool = soundPool ?: return
        for (tier in AchievementTier.entries) {
            val external = scanExternal(tier)
            val path = external ?: File(cacheDir, "${tier.name.lowercase()}.wav").takeIf { it.exists() }?.absolutePath
            if (path != null) {
                try {
                    val id = pool.load(path, 1)
                    if (id != 0) soundIds[tier] = id
                } catch (_: Exception) {}
                continue
            }
            tierBundledRes(tier)?.let { res ->
                try {
                    val id = pool.load(context, res, 1)
                    if (id != 0) soundIds[tier] = id
                } catch (_: Exception) {}
            }
        }
    }

    private fun tierBundledRes(tier: AchievementTier): Int? {
        return try {
            val r = eu.kanade.tachiyomi.R.raw::class.java
            val field = when (tier) {
                AchievementTier.BRONZE -> r.getField("moan_common_1")
                AchievementTier.SILVER -> r.getField("moan_common_2")
                AchievementTier.GOLD -> r.getField("moan_rare_1")
                AchievementTier.PLATINUM -> r.getField("moan_rare_2")
                AchievementTier.LEGENDARY -> r.getField("moan_legendary_1")
                AchievementTier.MYTHIC -> r.getField("moan_legendary_1")
            }
            field.getInt(null)
        } catch (_: Exception) {
            null
        }
    }

    private fun scanExternal(tier: AchievementTier): String? {
        val dir = File(File(context.filesDir, "achievement_sounds"), tier.name.lowercase())
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("mp3", "wav", "ogg", "oga", "m4a", "flac") }
            ?.sortedBy { it.name.lowercase() }?.firstOrNull()?.absolutePath
    }

    fun play(tier: AchievementTier) {
        if (!prefs.achievementsEnabled().get()) return
        if (!prefs.achievementSoundsEnabled().get()) return
        val pool = soundPool
        val id = soundIds[tier]
        if (pool != null && id != null && id != 0) {
            val vol = when (tier) {
                AchievementTier.BRONZE -> 0.75f
                AchievementTier.SILVER -> 0.8f
                AchievementTier.GOLD -> 0.88f
                AchievementTier.PLATINUM -> 0.92f
                AchievementTier.LEGENDARY -> 1.0f
                AchievementTier.MYTHIC -> 1.0f
            }
            val rate = when (tier) {
                AchievementTier.BRONZE -> 1.0f
                AchievementTier.SILVER -> 1.0f
                AchievementTier.GOLD -> 1.0f
                AchievementTier.PLATINUM -> 1.0f
                AchievementTier.LEGENDARY -> 1.0f
                AchievementTier.MYTHIC -> 1.0f
            }
            try {
                pool.play(id, vol, vol, 1, 0, rate)
            } catch (_: Exception) {}
        }
    }

    fun reload() {
        soundPool?.let { pool -> soundIds.values.forEach { runCatching { pool.unload(it) } } }
        soundIds.clear()
        ensureChimesExist()
        loadAll()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }

    // Pleasant bell synthesis — sine with harmonics + exponential decay, not telephone beeps.
    private fun synthesizeChime(tier: AchievementTier): ByteArray {
        val sr = 44100
        val wav = when (tier) {
            AchievementTier.BRONZE -> bellWav(sr, listOf(1046.5), durations = listOf(0.45), decay = 2.8, harmonics = listOf(1.0 to 1.0, 2.0 to 0.35, 3.0 to 0.12))
            AchievementTier.SILVER -> {
                val a = bellTone(sr, 1318.5, 0.22, 3.5)
                val b = bellTone(sr, 1568.0, 0.26, 3.2)
                concatPcm(a, b, gapMs = 30, sr = sr)
            }
            AchievementTier.GOLD -> chordWav(sr, listOf(1046.5, 1318.5, 1568.0), 0.55, 2.6)
            AchievementTier.PLATINUM -> arpeggioWav(sr, listOf(1046.5, 1318.5, 1568.0, 2093.0), each = 0.14, decay = 2.9)
            AchievementTier.LEGENDARY -> glissWav(sr, from = 523.25, to = 2093.0, duration = 0.9, decay = 2.2)
            AchievementTier.MYTHIC -> mythicWav(sr)
        }
        return wav
    }

    private fun bellTone(sr: Int, freq: Double, dur: Double, decay: Double): ShortArray {
        val n = (sr * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-decay * t) * (1 - exp(-40 * t))
            var s = 0.0
            s += sin(2 * PI * freq * t) * 1.0
            s += sin(2 * PI * freq * 2.0 * t) * 0.28
            s += sin(2 * PI * freq * 3.0 * t) * 0.12
            s += sin(2 * PI * freq * 4.0 * t) * 0.04
            out[i] = (s * env * 0.28 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun bellWav(sr: Int, freqs: List<Double>, durations: List<Double>, decay: Double, harmonics: List<Pair<Double, Double>>): ByteArray {
        val total = freqs.zip(durations).sumOf { (sr * it.second).toInt() }
        val pcm = ShortArray(total)
        var off = 0
        for ((idx, f) in freqs.withIndex()) {
            val d = durations[idx]
            val n = (sr * d).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = exp(-decay * t) * (1 - exp(-30 * t))
                var s = 0.0
                for ((mult, amp) in harmonics) s += sin(2 * PI * f * mult * t) * amp
                val pos = off + i
                if (pos < pcm.size) pcm[pos] = (s * env * 0.22 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            off += n
        }
        return pcmToWav(pcm, sr)
    }

    private fun chordWav(sr: Int, freqs: List<Double>, dur: Double, decay: Double): ByteArray {
        val n = (sr * dur).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-decay * t) * (1 - exp(-50 * t))
            var s = 0.0
            for (f in freqs) {
                s += sin(2 * PI * f * t) * 0.33
                s += sin(2 * PI * f * 2 * t) * 0.08
            }
            pcm[i] = (s * env * 0.22 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return pcmToWav(pcm, sr)
    }

    private fun arpeggioWav(sr: Int, freqs: List<Double>, each: Double, decay: Double): ByteArray {
        val nEach = (sr * each).toInt()
        val gap = (sr * 0.015).toInt()
        val total = freqs.size * nEach + (freqs.size - 1) * gap
        val pcm = ShortArray(total)
        var off = 0
        for (f in freqs) {
            val tone = bellTone(sr, f, each, decay)
            for (i in tone.indices) pcm[off + i] = (pcm[off + i] + tone[i] * 0.9).toInt().coerceIn(-32767, 32767).toShort()
            off += nEach + gap
        }
        return pcmToWav(pcm, sr)
    }

    private fun glissWav(sr: Int, from: Double, to: Double, duration: Double, decay: Double): ByteArray {
        val n = (sr * duration).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val prog = t / duration
            val freq = from * Math.pow(to / from, prog)
            val env = exp(-decay * t * 0.6) * (0.5 + 0.5 * exp(-8 * t))
            var s = sin(2 * PI * freq * t) * 0.5
            s += sin(2 * PI * freq * 2 * t) * 0.15
            s += sin(2 * PI * freq * 3 * t) * 0.06
            pcm[i] = (s * env * 0.3 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return pcmToWav(pcm, sr)
    }

    private fun mythicWav(sr: Int): ByteArray {
        val dur = 1.15
        val n = (sr * dur).toInt()
        val pcm = ShortArray(n)
        val base = listOf(261.63, 523.25, 659.25, 783.99, 987.77)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-1.8 * t) * (1 - exp(-20 * t)) * (1 + 0.15 * sin(2 * PI * 0.9 * t))
            var s = 0.0
            for (f in base) {
                s += sin(2 * PI * f * t) * 0.18
                s += sin(2 * PI * f * 2 * t) * 0.06
            }
            s += sin(2 * PI * 1318.5 * t) * 0.06 * exp(-2.5 * t)
            pcm[i] = (s * env * 0.26 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return pcmToWav(pcm, sr)
    }

    private fun concatPcm(a: ShortArray, b: ShortArray, gapMs: Int, sr: Int): ByteArray {
        val gap = (sr * gapMs / 1000.0).toInt()
        val out = ShortArray(a.size + gap + b.size)
        a.copyInto(out, 0, 0, a.size)
        b.copyInto(out, a.size + gap, 0, b.size)
        return pcmToWav(out, sr)
    }

    private fun pcmToWav(pcm: ShortArray, sr: Int): ByteArray {
        val bytes = ByteArray(44 + pcm.size * 2)
        fun writeInt(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[off + 2] = ((v shr 16) and 0xFF).toByte()
            bytes[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun writeShort(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        bytes[0] = 0x52.toByte()
        bytes[1] = 0x49.toByte()
        bytes[2] = 0x46.toByte()
        bytes[3] = 0x46.toByte()
        writeInt(4, 36 + pcm.size * 2)
        bytes[8] = 0x57.toByte()
        bytes[9] = 0x41.toByte()
        bytes[10] = 0x56.toByte()
        bytes[11] = 0x45.toByte()
        bytes[12] = 0x66.toByte()
        bytes[13] = 0x6D.toByte()
        bytes[14] = 0x74.toByte()
        bytes[15] = 0x20.toByte()
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, 1)
        writeInt(24, sr)
        writeInt(28, sr * 2)
        writeShort(32, 2)
        writeShort(34, 16)
        bytes[36] = 0x64.toByte()
        bytes[37] = 0x61.toByte()
        bytes[38] = 0x74.toByte()
        bytes[39] = 0x61.toByte()
        writeInt(40, pcm.size * 2)
        var off = 44
        for (s in pcm) {
            writeShort(off, s.toInt())
            off += 2
        }
        return bytes
    }

    companion object {
        const val EXTERNAL_DIR = "achievement_sounds"
    }
}
