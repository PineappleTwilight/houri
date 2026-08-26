package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.kmk.KMR

enum class NavigationRailAlignment(val titleRes: StringResource) {
    TOP(KMR.strings.rail_alignment_top),
    CENTER(KMR.strings.rail_alignment_center),
    BOTTOM(KMR.strings.rail_alignment_bottom),
}
