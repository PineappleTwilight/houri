package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

/** Advanced engine tuning — every value defaults to the library's proven default.  */
class SettingsYakuyomiEngineAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_yakuyomi_engine_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }

        val inputSize by prefs.detectorInputSize().collectAsState()
        val boxThresh by prefs.detectorBoxThreshold().collectAsState()
        val segThresh by prefs.detectorSegThreshold().collectAsState()
        val ocrProb by prefs.ocrMinProb().collectAsState()
        val bicubic by prefs.ocrBicubic().collectAsState()
        val unsharp by prefs.ocrUnsharp().collectAsState()
        val method by prefs.inpainterMethod().collectAsState()
        val tile by prefs.inpainterTileSize().collectAsState()
        val dilate by prefs.inpainterMaskDilate().collectAsState()
        val pad by prefs.inpainterBboxPad().collectAsState()
        val fontScale by prefs.renderFontScale().collectAsState()
        val expandW by prefs.renderExpandW().collectAsState()
        val expandH by prefs.renderExpandH().collectAsState()
        val tcy by prefs.renderTateChuYoko().collectAsState()
        val fontMax by prefs.renderFontSizeMax().collectAsState()
        val fontMin by prefs.renderFontSizeMin().collectAsState()

        return listOf(
            Preference.PreferenceGroup(
                title = tachiyomi.presentation.core.i18n.stringResource(KMR.strings.pref_yakuyomi_engine_advanced_warning_title),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = tachiyomi.presentation.core.i18n.stringResource(KMR.strings.pref_yakuyomi_engine_advanced_warning),
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "Detection",
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = inputSize,
                        title = "Input size",
                        subtitle = "Detector input long side (default 1024): %s",
                        valueString = inputSize.toString(),
                        valueRange = 512..1536,
                        steps = 7,
                        onValueChanged = { prefs.detectorInputSize().set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (boxThresh * 100).toInt(),
                        title = "Box threshold",
                        subtitle = "DB box score cutoff (default 0.70): %s",
                        valueString = String.format(Locale.US, "%.2f", boxThresh),
                        valueRange = 30..90,
                        steps = 59,
                        onValueChanged = { prefs.detectorBoxThreshold().set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (segThresh * 100).toInt(),
                        title = "Seg threshold",
                        subtitle = "Stroke mask threshold (default 0.12): %s",
                        valueString = String.format(Locale.US, "%.2f", segThresh),
                        valueRange = 5..40,
                        steps = 34,
                        onValueChanged = { prefs.detectorSegThreshold().set(it / 100f) },
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "OCR",
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = (ocrProb * 100).toInt(),
                        title = "Min confidence",
                        subtitle = "Drop OCR below this (default 0.50): %s",
                        valueString = String.format(Locale.US, "%.2f", ocrProb),
                        valueRange = 20..90,
                        steps = 69,
                        onValueChanged = { prefs.ocrMinProb().set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.ocrBicubic(),
                        title = "Bicubic warp",
                        subtitle = "Hand-rolled bicubic perspective (sharper small kana)",
                        enabled = true,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.ocrUnsharp(),
                        title = "Unsharp mask",
                        subtitle = "Sharpen OCR strips (restores blurred small kana)",
                        enabled = true,
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "Inpainting",
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.inpainterMethod(),
                        entries = mapOf("aot" to "AOT-GAN (quality)", "boxfill" to "Box-fill (fast)").let {
                            kotlinx.collections.immutable.persistentMapOf(*it.entries.map { e -> e.key to e.value }.toTypedArray())
                        },
                        title = "Method",
                        subtitle = "AOT reconstructs art, box-fill is flat color: %s",
                        enabled = true,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = tile,
                        title = "AOT tile size",
                        subtitle = "Whole-page AOT resolution (default 768): %s",
                        valueString = tile.toString(),
                        valueRange = 256..1024,
                        steps = 11,
                        onValueChanged = { prefs.inpainterTileSize().set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = dilate.toInt(),
                        title = "Mask dilate",
                        subtitle = "Swallow white outline (default 24): %s",
                        valueString = String.format(Locale.US, "%.0f", dilate),
                        valueRange = 4..48,
                        steps = 43,
                        onValueChanged = { prefs.inpainterMaskDilate().set(it.toFloat()) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = pad,
                        title = "BBox pad",
                        subtitle = "Expand bbox for inpaint (default 16): %s",
                        valueString = pad.toString(),
                        valueRange = 0..32,
                        steps = 31,
                        onValueChanged = { prefs.inpainterBboxPad().set(it) },
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "Typesetting",
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = (fontScale * 100).toInt(),
                        title = "Font scale",
                        subtitle = "Overall shrink (default 0.85): %s",
                        valueString = String.format(Locale.US, "%.2f", fontScale),
                        valueRange = 60..120,
                        steps = 59,
                        onValueChanged = { prefs.renderFontScale().set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (expandW * 100).toInt(),
                        title = "Expand W",
                        subtitle = "Horizontal breathing room (default 1.3): %s",
                        valueString = String.format(Locale.US, "%.2f", expandW),
                        valueRange = 100..200,
                        steps = 99,
                        onValueChanged = { prefs.renderExpandW().set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (expandH * 100).toInt(),
                        title = "Expand H",
                        subtitle = "Vertical breathing room (default 1.5): %s",
                        valueString = String.format(Locale.US, "%.2f", expandH),
                        valueRange = 100..200,
                        steps = 99,
                        onValueChanged = { prefs.renderExpandH().set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.renderTateChuYoko(),
                        title = "Tate-chu-yoko",
                        subtitle = "Merge 2-4 ASCII chars in vertical columns",
                        enabled = true,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = fontMax,
                        title = "Font max",
                        subtitle = "Largest font (default 60): %s",
                        valueString = fontMax.toString(),
                        valueRange = 30..100,
                        steps = 69,
                        onValueChanged = { prefs.renderFontSizeMax().set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = fontMin,
                        title = "Font min",
                        subtitle = "Smallest font before overflow handling (default 9): %s",
                        valueString = fontMin.toString(),
                        valueRange = 6..20,
                        steps = 13,
                        onValueChanged = { prefs.renderFontSizeMin().set(it) },
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "Reset to library defaults",
                        subtitle = "Restore all engine values to the proven defaults",
                        onClick = { prefs.resetEngineTuning() },
                    ),
                ).toPersistentList(),
            ),
        )
    }
}
