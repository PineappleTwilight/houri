package tachiyomi.domain.library.model

sealed interface LibraryDisplayMode {

    data object CompactGrid : LibraryDisplayMode
    data object ComfortableGrid : LibraryDisplayMode

    // KMK -->
    data object ComfortableGridPanorama : LibraryDisplayMode
    data object StaggeredGrid : LibraryDisplayMode

    // KMK <--
    data object List : LibraryDisplayMode
    data object CoverOnlyGrid : LibraryDisplayMode

    object Serializer {
        fun deserialize(serialized: String): LibraryDisplayMode {
            return LibraryDisplayMode.deserialize(serialized)
        }

        fun serialize(value: LibraryDisplayMode): String {
            return value.serialize()
        }
    }

    companion object {
        val values by lazy { setOf(CompactGrid, ComfortableGrid, ComfortableGridPanorama, StaggeredGrid, List, CoverOnlyGrid) }
        val default = CompactGrid

        fun deserialize(serialized: String): LibraryDisplayMode {
            return when (serialized) {
                "COMFORTABLE_GRID" -> ComfortableGrid
                // KMK -->
                "COMFORTABLE_GRID_PANORAMA" -> ComfortableGridPanorama
                "STAGGERED_GRID" -> StaggeredGrid
                // KMK <--
                "COMPACT_GRID" -> CompactGrid
                "COVER_ONLY_GRID" -> CoverOnlyGrid
                "LIST" -> List
                else -> default
            }
        }
    }

    fun serialize(): String {
        return when (this) {
            ComfortableGrid -> "COMFORTABLE_GRID"
            // KMK -->
            ComfortableGridPanorama -> "COMFORTABLE_GRID_PANORAMA"
            StaggeredGrid -> "STAGGERED_GRID"
            // KMK <--
            CompactGrid -> "COMPACT_GRID"
            CoverOnlyGrid -> "COVER_ONLY_GRID"
            List -> "LIST"
        }
    }
}
