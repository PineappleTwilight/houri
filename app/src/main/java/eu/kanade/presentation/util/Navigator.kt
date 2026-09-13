package eu.kanade.presentation.util

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransitionContent
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import logcat.LogPriority
import logcat.logcat
import mihon.core.concurrency.AppDispatchersHolder

/**
 * For invoking back press to the parent activity
 */
val LocalBackPress: ProvidableCompositionLocal<(() -> Unit)?> = staticCompositionLocalOf { null }

interface Tab : cafe.adriel.voyager.navigator.tab.Tab {
    suspend fun onReselect(navigator: Navigator) {}

    // SY -->
    @Composable
    fun isEnabled(): Boolean = true
    // SY <--
}

abstract class Screen : Screen {
    override val key: ScreenKey = "${this::class.qualifiedName}#$uniqueScreenKey#${System.identityHashCode(this)}"
}

/**
 * A variant of ScreenModel.coroutineScope except with the IO dispatcher instead of the
 * main dispatcher.
 */
val ScreenModel.ioCoroutineScope: CoroutineScope
    get() = ScreenModelStore.getOrPutDependency(
        screenModel = this,
        name = "ScreenModelIoCoroutineScope",
        factory = { key -> CoroutineScope(AppDispatchersHolder.get().io + SupervisorJob()) + CoroutineName(key) },
        onDispose = { scope -> scope.cancel() },
    )

interface AssistContentScreen {
    fun onProvideAssistUrl(): String?
}

// KMK -->
/**
 * Pure slide + fade transition builder for Voyager push/pop.
 *
 * @param pop true when the transition is a stack pop (mirrors the slide direction).
 */
fun navigatorTransition(pop: Boolean): AnimatedContentTransitionScope<Screen>.() -> ContentTransform = {
    val enterOffset: AnimatedContentTransitionScope<Screen>.(Int) -> Int =
        { if (pop) -it / 6 else it / 6 }
    val exitOffset: AnimatedContentTransitionScope<Screen>.(Int) -> Int =
        { if (pop) it / 6 else -it / 6 }
    slideInHorizontally(tween(UiMotion.ScreenEnter, easing = UiMotion.Emphasized), enterOffset) +
        fadeIn(tween(UiMotion.ScreenEnter, easing = UiMotion.Emphasized)) togetherWith
        slideOutHorizontally(tween(UiMotion.ScreenExit, easing = UiMotion.Emphasized), exitOffset) +
        fadeOut(tween(UiMotion.ScreenExit, easing = UiMotion.Emphasized))
}

@Composable
fun DefaultNavigatorScreenTransition(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    ScreenTransition(
        navigator = navigator,
        transition = {
            navigatorTransition(navigator.lastEvent == StackEvent.Pop).invoke(this)
        },
        modifier = modifier,
    )
}
// KMK <--

@Composable
fun ScreenTransition(
    navigator: Navigator,
    transition: AnimatedContentTransitionScope<Screen>.() -> ContentTransform,
    modifier: Modifier = Modifier,
    content: ScreenTransitionContent = { it.Content() },
) {
    AnimatedContent(
        targetState = navigator.lastItem,
        transitionSpec = { transition() },
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        label = "screen-transition",
    ) { screen ->
        if (isPreviewBuildType) {
            logcat(LogPriority.ERROR) { "ScreenTransition: ${screen.key}" }
        }
        val saveableKey = remember(screen.key) {
            "screen-transition-${screen.key}"
        }
        navigator.saveableState(saveableKey, screen) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                content(screen)
            }
        }
    }
}
