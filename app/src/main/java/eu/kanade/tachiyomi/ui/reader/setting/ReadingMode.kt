package eu.kanade.tachiyomi.ui.reader.setting

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import tachiyomi.i18n.MR

enum class ReadingMode(
    val stringRes: StringResource,
    @DrawableRes val iconRes: Int,
    val flagValue: Int,
    val direction: Direction? = null,
    val type: ViewerType? = null,
) {
    DEFAULT(MR.strings.label_default, R.drawable.ic_reader_default_24dp, 0x00000000),
    LEFT_TO_RIGHT(
        MR.strings.left_to_right_viewer,
        R.drawable.ic_reader_ltr_24dp,
        0x00000001,
        Direction.Horizontal,
        ViewerType.Pager,
    ),
    RIGHT_TO_LEFT(
        MR.strings.right_to_left_viewer,
        R.drawable.ic_reader_rtl_24dp,
        0x00000002,
        Direction.Horizontal,
        ViewerType.Pager,
    ),
    VERTICAL(
        MR.strings.vertical_viewer,
        R.drawable.ic_reader_vertical_24dp,
        0x00000003,
        Direction.Vertical,
        ViewerType.Pager,
    ),
    WEBTOON(
        MR.strings.webtoon_viewer,
        R.drawable.ic_reader_webtoon_24dp,
        0x00000004,
        Direction.Vertical,
        ViewerType.Webtoon,
    ),
    CONTINUOUS_VERTICAL(
        MR.strings.vertical_plus_viewer,
        R.drawable.ic_reader_continuous_vertical_24dp,
        0x00000005,
        Direction.Vertical,
        ViewerType.Webtoon,
    ),
    ;

    companion object {
        const val MASK = 0x00000007

        fun fromPreference(preference: Int?): ReadingMode = entries.find { it.flagValue == preference } ?: DEFAULT

        fun isPagerType(preference: Int): Boolean {
            val mode = fromPreference(preference)
            return mode.type is ViewerType.Pager
        }

        fun toViewer(
            preference: Int?,
            activity: ReaderActivity,
            // KMK -->
            @ColorInt seedColor: Int?,
            // KMK <--
        ): Viewer {
            val mode = fromPreference(preference)
            if (mode == DEFAULT) throw IllegalStateException("Preference value must be resolved: $preference")
            // Viewer construction is pluggable via ViewerRegistry (WebGPU gate +
            // legacy mapping live in the default providers); custom readers register
            // their own ViewerProvider.
            return eu.kanade.tachiyomi.ui.reader.viewer.ViewerRegistry.create(mode, activity, seedColor)
        }
    }

    sealed interface Direction {
        data object Horizontal : Direction
        data object Vertical : Direction
    }

    sealed interface ViewerType {
        data object Pager : ViewerType
        data object Webtoon : ViewerType
    }
}
