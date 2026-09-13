package eu.kanade.presentation.util

import androidx.compose.animation.core.CubicBezierEasing
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class UiMotionTest {

    @Test
    fun `emphasized easing matches material 3 control points`() {
        val emphasized = UiMotion.EMPHASIZED as CubicBezierEasing

        emphasized.a shouldBe 0.2f
        emphasized.b shouldBe 0f
        emphasized.c shouldBe 0f
        emphasized.d shouldBe 1f
    }

    @Test
    fun `durations are positive and enter outlasts exit`() {
        (UiMotion.SCREEN_ENTER > 0) shouldBe true
        (UiMotion.SCREEN_EXIT > 0) shouldBe true
        (UiMotion.TAB_DURATION > 0) shouldBe true
        (UiMotion.SCREEN_ENTER > UiMotion.SCREEN_EXIT) shouldBe true
    }
}
