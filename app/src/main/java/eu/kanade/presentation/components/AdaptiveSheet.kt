package eu.kanade.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.lifecycle.DisposableEffectIgnoringConfiguration
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.UiMotion
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.presentation.core.components.AdaptiveSheet as AdaptiveSheetImpl

@OptIn(InternalVoyagerApi::class)
@Composable
fun NavigatorAdaptiveSheet(
    screen: Screen,
    enableSwipeDismiss: (Navigator) -> Boolean = { true },
    onDismissRequest: () -> Unit,
) {
    Navigator(
        screen = screen,
        content = { sheetNavigator ->
            AdaptiveSheet(
                enableSwipeDismiss = enableSwipeDismiss(sheetNavigator),
                onDismissRequest = onDismissRequest,
            ) {
                // KMK --> sheet content must wrap instead of reusing the full-screen
                // ScreenTransition (fillMaxSize + background), which stretched the
                // sheet full height and left empty space below short cards.
                AnimatedContent(
                    targetState = sheetNavigator.lastItem,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(
                                UiMotion.SHEET_FADE_IN,
                                delayMillis = UiMotion.SHEET_FADE_OUT,
                                easing = UiMotion.EMPHASIZED_DECELERATE,
                            ),
                        ) togetherWith
                            fadeOut(
                                animationSpec = tween(
                                    UiMotion.SHEET_FADE_OUT,
                                    easing = UiMotion.EMPHASIZED_ACCELERATE,
                                ),
                            )
                    },
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    label = "sheet-transition",
                ) { sheetScreen ->
                    sheetNavigator.saveableState("sheet-transition-${sheetScreen.key}", sheetScreen) {
                        sheetScreen.Content()
                    }
                }
                // KMK <--

                BackHandler(
                    enabled = sheetNavigator.size > 1,
                    onBack = sheetNavigator::pop,
                )
            }

            // Make sure screens are disposed no matter what
            if (sheetNavigator.parent?.disposeBehavior?.disposeNestedNavigators == false) {
                DisposableEffectIgnoringConfiguration {
                    onDispose {
                        sheetNavigator.items
                            .asReversed()
                            .forEach(sheetNavigator::dispose)
                    }
                }
            }
        },
    )
}

/**
 * Sheet with adaptive position aligned to bottom on small screen, otherwise aligned to center
 * and will not be able to dismissed with swipe gesture.
 *
 * Max width of the content is set to 460 dp.
 */
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enableSwipeDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isTabletUi = isTabletUi()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = dialogProperties,
    ) {
        AdaptiveSheetImpl(
            modifier = modifier,
            isTabletUi = isTabletUi,
            enableSwipeDismiss = enableSwipeDismiss,
            onDismissRequest = onDismissRequest,
        ) {
            content()
        }
    }
}

private val dialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = true,
)
