package exh.util

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.DataSaver.BANDWIDTH_HERO
import eu.kanade.domain.source.service.SourcePreferences.DataSaver.NONE
import eu.kanade.domain.source.service.SourcePreferences.DataSaver.WSRV_NL
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import tachiyomi.core.common.preference.Preference

interface DataSaver {

    fun compress(imageUrl: String): String

    companion object {
        val NoOp = object : DataSaver {
            override fun compress(imageUrl: String): String {
                return imageUrl
            }
        }

        suspend fun HttpSource.getImage(page: Page, dataSaver: DataSaver): Response {
            val imageUrl = page.imageUrl ?: return getImage(page)
            if (imageUrl.isBlank() || imageUrl.startsWith("data:", true) || imageUrl.startsWith("blob:", true)) {
                return getImage(page)
            }
            val compressed = try {
                dataSaver.compress(imageUrl)
            } catch (_: Exception) {
                imageUrl
            }
            if (compressed == imageUrl || compressed.isBlank()) return getImage(page)
            page.imageUrl = compressed
            val compressedResponse = try {
                getImage(page)
            } catch (e: Exception) {
                page.imageUrl = imageUrl
                return try {
                    getImage(page)
                } catch (fallback: Exception) {
                    throw e
                }
            }
            if (compressedResponse.code in setOf(403, 404, 429, 500, 502, 503) || !compressedResponse.isSuccessful) {
                if (compressedResponse.code in setOf(403, 404, 429, 500, 502, 503)) {
                    compressedResponse.close()
                    page.imageUrl = imageUrl
                    return try {
                        getImage(page)
                    } finally {
                        page.imageUrl = imageUrl
                    }
                }
            }
            try {
                try {
                    mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("data_saver")
                    mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_daily_data_saver_5")
                    mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_weekly_data_saver_20")
                } catch (_: Exception) {}
                return compressedResponse
            } finally {
                page.imageUrl = imageUrl
            }
        }
    }
}

fun DataSaver(source: Source, preferences: SourcePreferences): DataSaver {
    val dataSaver = preferences.dataSaver().get()
    if (dataSaver != NONE && source.id.toString() in preferences.dataSaverExcludedSources().get()) {
        return DataSaver.NoOp
    }
    return when (dataSaver) {
        NONE -> DataSaver.NoOp
        BANDWIDTH_HERO -> BandwidthHeroDataSaver(preferences)
        WSRV_NL -> WsrvNlDataSaver(preferences)
    }
}

private class BandwidthHeroDataSaver(preferences: SourcePreferences) : DataSaver {
    private val dataSavedServer = preferences.dataSaverServer().get().trim().trimEnd('/').let {
        if (it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
    }
    private val serverValid = dataSavedServer.isNotBlank() && (dataSavedServer.startsWith("http://") || dataSavedServer.startsWith("https://"))

    private val ignoreJpg = preferences.dataSaverIgnoreJpeg().get()
    private val ignoreGif = preferences.dataSaverIgnoreGif().get()

    private val format = preferences.dataSaverImageFormatJpeg().toIntRepresentation()
    private val quality = preferences.dataSaverImageQuality().get().coerceIn(0, 100)
    private val colorBW = preferences.dataSaverColorBW().toIntRepresentation()

    override fun compress(imageUrl: String): String {
        if (!serverValid) return imageUrl
        if (imageUrl.contains(dataSavedServer, ignoreCase = true)) return imageUrl
        if (imageUrl.length > 1800) return imageUrl
        if (imageUrl.startsWith("/") && !imageUrl.startsWith("//")) return imageUrl
        return when {
            imageUrl.contains(".jpeg", true) || imageUrl.contains(".jpg", true) -> if (ignoreJpg) imageUrl else getUrl(imageUrl)
            imageUrl.contains(".gif", true) -> if (ignoreGif) imageUrl else getUrl(imageUrl)
            else -> getUrl(imageUrl)
        }
    }

    private fun getUrl(imageUrl: String): String {
        val encoded = try {
            java.net.URLEncoder.encode(imageUrl, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            return imageUrl
        }
        if (encoded.length > 1900) return imageUrl
        return "$dataSavedServer/?jpg=$format&l=$quality&bw=$colorBW&url=$encoded"
    }

    private fun Preference<Boolean>.toIntRepresentation() = if (get()) "1" else "0"
}

private class WsrvNlDataSaver(preferences: SourcePreferences) : DataSaver {
    private val ignoreJpg = preferences.dataSaverIgnoreJpeg().get()
    private val ignoreGif = preferences.dataSaverIgnoreGif().get()

    private val format = preferences.dataSaverImageFormatJpeg().get()
    private val quality = preferences.dataSaverImageQuality().get().coerceIn(0, 100)

    override fun compress(imageUrl: String): String {
        if (imageUrl.startsWith("https://wsrv.nl", true)) return imageUrl
        if (imageUrl.startsWith("http://wsrv.nl", true)) return imageUrl
        if (imageUrl.startsWith("/") && !imageUrl.startsWith("//")) return imageUrl
        if (imageUrl.isBlank()) return imageUrl
        if (imageUrl.startsWith("data:", true) || imageUrl.startsWith("blob:", true)) return imageUrl
        if (imageUrl.length > 1800) return imageUrl
        return when {
            imageUrl.contains(".jpeg", true) || imageUrl.contains(".jpg", true) -> if (ignoreJpg) imageUrl else getUrl(imageUrl)
            imageUrl.contains(".gif", true) -> if (ignoreGif) imageUrl else getUrl(imageUrl)
            else -> getUrl(imageUrl)
        }
    }

    private fun getUrl(imageUrl: String): String {
        val encoded = try {
            java.net.URLEncoder.encode(imageUrl, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            return imageUrl
        }
        if (encoded.length > 1900) return imageUrl
        return "https://wsrv.nl/?url=$encoded" +
            if (imageUrl.contains(".webp", true) || imageUrl.contains(".gif", true)) {
                if (!format) {
                    "&q=$quality&n=-1"
                } else {
                    "&output=jpg&q=$quality&n=-1"
                }
            } else {
                if (format) {
                    "&output=jpg&q=$quality"
                } else {
                    "&output=webp&q=$quality"
                }
            }
    }
}
