package eu.kanade.tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.animeplanet.AnimePlanet
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.comick.ComicK
import eu.kanade.tachiyomi.data.track.core.TrackerId
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import kotlinx.coroutines.flow.combine

@Inject
@SingleIn(AppScope::class)
class TrackerManager {

    companion object {
        const val ANILIST = TrackerId.ANILIST
        const val KITSU = TrackerId.KITSU
        const val KAVITA = TrackerId.KAVITA
        const val HIKKA = TrackerId.HIKKA
        const val MANGABAKA = TrackerId.MANGABAKA
        const val ANIMEPLANET = TrackerId.ANIMEPLANET
        const val SUWAYOMI = TrackerId.SUWAYOMI
        const val COMICK = TrackerId.COMICK
        const val MDLIST = TrackerId.MDLIST
        const val MYANIMELIST = TrackerId.MYANIMELIST
        const val SHIKIMORI = TrackerId.SHIKIMORI
        const val BANGUMI = TrackerId.BANGUMI
        const val KOMGA = TrackerId.KOMGA
        const val MANGA_UPDATES = TrackerId.MANGA_UPDATES
    }

    val mdList = MdList(TrackerId.MDLIST)

    val myAnimeList = MyAnimeList(TrackerId.MYANIMELIST)
    val aniList = Anilist(TrackerId.ANILIST)
    val kitsu = Kitsu(TrackerId.KITSU)
    val shikimori = Shikimori(TrackerId.SHIKIMORI)
    val bangumi = Bangumi(TrackerId.BANGUMI)
    val komga = Komga(TrackerId.KOMGA)
    val mangaUpdates = MangaUpdates(TrackerId.MANGA_UPDATES)
    val kavita = Kavita(TrackerId.KAVITA)
    val suwayomi = Suwayomi(TrackerId.SUWAYOMI)
    val hikka = Hikka(TrackerId.HIKKA)
    val mangaBaka = MangaBaka(TrackerId.MANGABAKA)
    val animePlanet = AnimePlanet(TrackerId.ANIMEPLANET)
    val comicK = ComicK(TrackerId.COMICK)

    val trackers: List<Tracker> =
        listOf(mdList, myAnimeList, aniList, kitsu, shikimori, bangumi, komga, mangaUpdates, kavita, suwayomi, hikka, mangaBaka, animePlanet, comicK)

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }
}
