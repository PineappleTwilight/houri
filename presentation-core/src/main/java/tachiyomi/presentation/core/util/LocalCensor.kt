package tachiyomi.presentation.core.util

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal for censor state — modular replacement for
 * `globalAppGraph.uiPreferences.censorLewdManga().collectAsState()` that was
 * called **per grid item** (202 collectors for a typical library) in
 * `CommonMangaItem`. That pattern violated modularity (presentation reaching
 * into DI graph) and caused O(n) recompositions on toggle.
 *
 * The host (e.g. `LibraryContent`, `MangaScreen`) collects **once** and
 * provides via `CompositionLocalProvider(LocalCensorEnabled provides value)`.
 * Children consume with `LocalCensorEnabled.current` — zero collectors, pure
 * composition, previewable.
 */
val LocalCensorEnabled = compositionLocalOf { false }
