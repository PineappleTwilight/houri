package tachiyomi.domain.manga.model

/**
 * Startup-installed lookup for user-edited manga metadata, wired from the Metro app
 * graph during [App][android.app.Application] startup via `GetCustomMangaInfo`.
 *
 * Domain models ([Manga], [MangaCover], [HistoryWithRelations],
 * [UpdatesWithRelations]) snapshot custom info at construction but have no DI graph of
 * their own, so they read through this seam. It replaces a legacy Injekt
 * service-locator lookup that resolved a DIFFERENT `CustomMangaRepositoryImpl`
 * singleton than the one detail-edit writes went through - which made edits invisible
 * until process restart.
 *
 * Null until the app installs it; models then fall back to source metadata.
 */
object CustomMangaInfoLookup {
    @Volatile
    var resolve: ((mangaId: Long) -> CustomMangaInfo?)? = null
}
