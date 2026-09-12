package eu.kanade.tachiyomi.data.track.core

object TrackerId {
    const val MYANIMELIST = 1L
    const val ANILIST = 2L
    const val KITSU = 3L
    const val SHIKIMORI = 4L
    const val BANGUMI = 5L
    const val KOMGA = 6L
    const val MANGA_UPDATES = 7L
    const val KAVITA = 8L
    const val SUWAYOMI = 9L
    const val HIKKA = 10L
    const val MANGABAKA = 11L
    const val MDLIST = 60L
    const val ANIMEPLANET = 61L
    const val COMICK = 63L

    val all = setOf(
        MYANIMELIST, ANILIST, KITSU, SHIKIMORI, BANGUMI, KOMGA, MANGA_UPDATES,
        KAVITA, SUWAYOMI, HIKKA, MANGABAKA, MDLIST, ANIMEPLANET, COMICK,
    )
}
