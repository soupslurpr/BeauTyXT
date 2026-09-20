package dev.soupslurpr.beautyxt.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val MAX_SCALE_REDUCTION = 0.1f
private const val MAX_HORIZONTAL_TRANSLATION_FRACTION = 0.055f
private const val MAX_VERTICAL_TRANSLATION_FRACTION = 0.018f
private val PredictiveBackCornerRadius = 52.dp

/** Describes the bounded visual transform for one predictive back frame. */
internal data class PredictiveBackTransform(
    val progress: Float,
    val scale: Float,
    val horizontalTranslationFraction: Float
)

/** Calculates an edge-aware, bounded predictive back transform. */
internal fun calculatePredictiveBackTransform(
    progress: Float,
    swipeEdge: Int
): PredictiveBackTransform {
    require(progress.isFinite() && progress in 0f..1f) {
        "predictive back progress must be finite and between zero and one"
    }
    require(
        swipeEdge == BackEventCompat.EDGE_LEFT ||
            swipeEdge == BackEventCompat.EDGE_RIGHT ||
            swipeEdge == BackEventCompat.EDGE_NONE
    ) {
        "predictive back edge is invalid"
    }
    val easedProgress = FastOutSlowInEasing.transform(progress)
    val direction =
        when (swipeEdge) {
            BackEventCompat.EDGE_LEFT -> 1f
            BackEventCompat.EDGE_RIGHT -> -1f
            else -> 0f
        }
    return PredictiveBackTransform(
        progress = easedProgress,
        scale = 1f - (MAX_SCALE_REDUCTION * easedProgress),
        horizontalTranslationFraction =
            direction * MAX_HORIZONTAL_TRANSLATION_FRACTION * easedProgress
    )
}

/** Holds gesture progress without retaining document or navigation state. */
@Stable
internal class PredictiveBackMotionState {
    private val animatedProgress = Animatable(0f)

    internal val progress: Float
        get() = animatedProgress.value

    internal var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
        private set

    internal var touchY by mutableFloatStateOf(0f)
        private set

    /** Applies one system-provided predictive back event immediately. */
    internal suspend fun update(event: BackEventCompat) {
        swipeEdge = event.swipeEdge
        touchY = event.touchY
        animatedProgress.snapTo(event.progress.coerceIn(0f, 1f))
    }

    /** Returns immediately to the resting state after completed navigation. */
    internal suspend fun reset() {
        animatedProgress.snapTo(0f)
        swipeEdge = BackEventCompat.EDGE_NONE
        touchY = 0f
    }

    /** Settles a cancelled gesture smoothly back into place. */
    internal suspend fun settle() {
        animatedProgress.animateTo(
            targetValue = 0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
        )
        swipeEdge = BackEventCompat.EDGE_NONE
        touchY = 0f
    }
}

/** Remembers one screen-scoped predictive back motion state. */
@Composable
internal fun rememberPredictiveBackMotionState(): PredictiveBackMotionState =
    remember { PredictiveBackMotionState() }

/** Connects system back progress to optional interception and completion callbacks. */
@Composable
internal fun PredictiveBackMotionHandler(
    state: PredictiveBackMotionState,
    enabled: Boolean = true,
    interceptBackAtStart: () -> Boolean = { false },
    onInterceptedBackCancelled: () -> Unit = {},
    onInterceptedBack: () -> Unit = {},
    onBack: () -> Unit
) {
    val currentInterceptBackAtStart by rememberUpdatedState(interceptBackAtStart)
    val currentOnInterceptedBackCancelled by
        rememberUpdatedState(onInterceptedBackCancelled)
    val currentOnInterceptedBack by rememberUpdatedState(onInterceptedBack)
    val currentOnBack by rememberUpdatedState(onBack)
    val animationScope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = enabled) { events ->
        val intercepted = currentInterceptBackAtStart()
        try {
            if (intercepted) {
                events.collect { _ -> }
                currentOnInterceptedBack()
            } else {
                events.collect(state::update)
                currentOnBack()
                state.reset()
            }
        } catch (cancellation: CancellationException) {
            if (intercepted) {
                currentOnInterceptedBackCancelled()
            } else {
                animationScope.settlePredictiveBack(state)
            }
            throw cancellation
        }
    }
}

/** Applies the current predictive back transform without changing layout bounds. */
internal fun Modifier.predictiveBackMotion(state: PredictiveBackMotionState): Modifier =
    graphicsLayer {
        val transform =
            calculatePredictiveBackTransform(
                progress = state.progress,
                swipeEdge = state.swipeEdge
            )
        val verticalOrigin =
            if (size.height > 0f && state.touchY > 0f) {
                (state.touchY / size.height).coerceIn(0f, 1f)
            } else {
                0.5f
            }
        scaleX = transform.scale
        scaleY = transform.scale
        translationX = size.width * transform.horizontalTranslationFraction
        translationY =
            size.height *
            (verticalOrigin - 0.5f) *
            MAX_VERTICAL_TRANSLATION_FRACTION *
            transform.progress
        transformOrigin = TransformOrigin(0.5f, verticalOrigin)
        shape = RoundedCornerShape(PredictiveBackCornerRadius * transform.progress)
        clip = transform.progress > 0f
    }

/** Starts cancellation settlement outside the cancelled gesture coroutine. */
private fun CoroutineScope.settlePredictiveBack(state: PredictiveBackMotionState) {
    launch { state.settle() }
}
