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
        val emphasized = UiMotion.Emphasized as CubicBezierEasing

        emphasized.a shouldBe 0.2f
        emphasized.b shouldBe 0f
        emphasized.c shouldBe 0f
        emphasized.d shouldBe 1f
    }

    @Test
    fun `durations are positive and enter outlasts exit`() {
        (UiMotion.ScreenEnter > 0) shouldBe true
        (UiMotion.ScreenExit > 0) shouldBe true
        (UiMotion.TabDuration > 0) shouldBe true
        (UiMotion.ScreenEnter > UiMotion.ScreenExit) shouldBe true
    }
}
