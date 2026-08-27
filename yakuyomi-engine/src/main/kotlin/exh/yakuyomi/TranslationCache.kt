package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.File
import java.security.MessageDigest

@SingleIn(AppScope::class)
@Inject
class TranslationCache(
    private val context: Context,
) {
    private fun cacheDir(): File = File(context.cacheDir, "yakuyomi").apply { mkdirs() }

    fun key(pageHash: String, targetLang: String, model: String): String {
        val raw = "$pageHash|$targetLang|$model"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"
    }

    fun pageHash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
    }

    fun getFile(pageHash: String, targetLang: String, model: String): File =
        File(cacheDir(), key(pageHash, targetLang, model))

    fun getIfExists(pageHash: String, targetLang: String, model: String): File? =
        getFile(pageHash, targetLang, model).takeIf { it.exists() && it.length() > 0 }

    fun put(pageHash: String, targetLang: String, model: String, webpBytes: ByteArray): File {
        val f = getFile(pageHash, targetLang, model)
        f.parentFile?.mkdirs()
        f.writeBytes(webpBytes)
        return f
    }

    fun hashBytes(bytes: ByteArray): String {
        val sink = HashingSink.blackholeSink().buffer()
        sink.write(bytes)
        sink.close()
        return pageHash(bytes)
    }
}
