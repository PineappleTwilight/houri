package eu.kanade.presentation.reader.settings

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ChapterCompleteSoundPackManager
import eu.kanade.tachiyomi.ui.reader.SoundTier
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.IconItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

@Composable
internal fun GeneralPage(screenModel: ReaderSettingsScreenModel) {
    val readerTheme by screenModel.preferences.readerTheme().collectAsState()

    val flashPageState by screenModel.preferences.flashOnPageChange().collectAsState()

    val flashMillisPref = screenModel.preferences.flashDurationMillis()
    val flashMillis by flashMillisPref.collectAsState()

    val flashIntervalPref = screenModel.preferences.flashPageInterval()
    val flashInterval by flashIntervalPref.collectAsState()

    val flashColorPref = screenModel.preferences.flashColor()
    val flashColor by flashColorPref.collectAsState()

    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { screenModel.preferences.readerTheme().set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = screenModel.preferences.showPageNumber(),
    )

    // SY -->
    val forceHorizontalSeekbar by screenModel.preferences.forceHorizontalSeekbar().collectAsState()
    CheckboxItem(
        label = stringResource(SYMR.strings.pref_force_horz_seekbar),
        pref = screenModel.preferences.forceHorizontalSeekbar(),
    )

    if (!forceHorizontalSeekbar) {
        CheckboxItem(
            label = stringResource(SYMR.strings.pref_show_vert_seekbar_landscape),
            pref = screenModel.preferences.landscapeVerticalSeekbar(),
        )

        CheckboxItem(
            label = stringResource(SYMR.strings.pref_left_handed_vertical_seekbar),
            pref = screenModel.preferences.leftVerticalSeekbar(),
        )

        // Mihon -->
        val verticalNavigatorHeight by screenModel.preferences.verticalNavigatorHeight().collectAsState()
        SliderItem(
            label = stringResource(MR.strings.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { screenModel.preferences.verticalNavigatorHeight().set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        // Mihon <--
    }
    // SY <--

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = screenModel.preferences.fullscreen(),
    )

    val isFullscreen by screenModel.preferences.fullscreen().collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = screenModel.preferences.drawUnderCutout(),
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = screenModel.preferences.keepScreenOn(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = screenModel.preferences.readWithLongTap(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = screenModel.preferences.alwaysShowChapterTransition(),
    )

    // KMK -->
    val chapterCompletionSoundEnabled by screenModel.preferences.chapterCompletionSound().collectAsState()
    CheckboxItem(
        label = stringResource(KMR.strings.pref_chapter_completion_sound),
        pref = screenModel.preferences.chapterCompletionSound(),
    )
    if (chapterCompletionSoundEnabled) {
        ChapterCompleteSoundSettings()
    }
    // KMK <--

    // SY -->
    /*CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = screenModel.preferences.pageTransitions(),
    ) SY <-- */

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = screenModel.preferences.flashOnPageChange(),
    )

    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }

    // SY -->
    CheckboxItem(
        label = stringResource(SYMR.strings.auto_webtoon_mode),
        pref = screenModel.preferences.useAutoWebtoon(),
    )
    // SY <--
}

// KMK -->
@Composable
private fun ChapterCompleteSoundSettings() {
    val context = LocalContext.current
    var pendingUris by remember { mutableStateOf<List<Uri>?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var importVersion by remember { mutableIntStateOf(0) }

    val customCounts = remember(importVersion) {
        ChapterCompleteSoundPackManager.customCounts(context)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) pendingUris = uris
    }

    IconItem(
        label = stringResource(KMR.strings.pref_chapter_completion_sound_import),
        icon = Icons.Outlined.LibraryMusic,
        onClick = { importLauncher.launch(arrayOf("audio/*")) },
    )

    if (customCounts.values.any { it > 0 }) {
        IconItem(
            label = stringResource(KMR.strings.pref_chapter_completion_sound_remove),
            icon = Icons.Outlined.Delete,
            onClick = { showRemoveConfirm = true },
        )
    }

    pendingUris?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingUris = null },
            title = { Text(text = stringResource(KMR.strings.pref_chapter_completion_sound_import_tier)) },
            text = {
                Column {
                    SoundTier.entries.forEach { tier ->
                        val labelRes = when (tier) {
                            SoundTier.COMMON -> KMR.strings.pref_chapter_completion_sound_tier_common
                            SoundTier.RARE -> KMR.strings.pref_chapter_completion_sound_tier_rare
                            SoundTier.LEGENDARY -> KMR.strings.pref_chapter_completion_sound_tier_legendary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable {
                                    val imported = ChapterCompleteSoundPackManager.import(context, uris, tier)
                                    importVersion++
                                    context.toast(
                                        if (imported > 0) {
                                            context.contextStringResource(
                                                KMR.strings.pref_chapter_completion_sound_imported,
                                                imported,
                                            )
                                        } else {
                                            context.contextStringResource(KMR.strings.pref_chapter_completion_sound_import_failed)
                                        },
                                    )
                                    pendingUris = null
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                            Text(text = stringResource(labelRes))
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(text = stringResource(KMR.strings.pref_chapter_completion_sound_remove)) },
            text = { Text(text = stringResource(KMR.strings.pref_chapter_completion_sound_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    ChapterCompleteSoundPackManager.clearAll(context)
                    importVersion++
                    context.toast(context.contextStringResource(KMR.strings.pref_chapter_completion_sound_removed))
                    showRemoveConfirm = false
                }) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
// KMK <--
