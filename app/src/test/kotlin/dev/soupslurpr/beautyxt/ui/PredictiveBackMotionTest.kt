package dev.soupslurpr.beautyxt.ui

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded and edge-aware predictive back transforms. */
class PredictiveBackMotionTest {
    /** Keeps the resting frame visually unchanged. */
    @Test
    fun restingTransformIsIdentity() {
        val transform =
            calculatePredictiveBackTransform(
                progress = 0f,
                swipeEdge = BackEventCompat.EDGE_LEFT
            )

        assertEquals(0f, transform.progress, 0f)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.horizontalTranslationFraction, 0f)
    }

    /** Moves toward the originating edge's opposite direction. */
    @Test
    fun edgesProduceMirroredTranslations() {
        val left = calculatePredictiveBackTransform(0.7f, BackEventCompat.EDGE_LEFT)
        val right = calculatePredictiveBackTransform(0.7f, BackEventCompat.EDGE_RIGHT)

        assertTrue(left.horizontalTranslationFraction > 0f)
        assertEquals(
            left.horizontalTranslationFraction,
            -right.horizontalTranslationFraction,
            0f
        )
        assertEquals(left.scale, right.scale, 0f)
    }

    /** Keeps the completed frame large enough to preserve spatial context. */
    @Test
    fun completedTransformRemainsBounded() {
        val transform =
            calculatePredictiveBackTransform(
                progress = 1f,
                swipeEdge = BackEventCompat.EDGE_LEFT
            )

        assertTrue(transform.scale in 0.9f..1f)
        assertTrue(transform.horizontalTranslationFraction in 0f..0.06f)
    }

    /** Rejects values that cannot originate from Android's progress contract. */
    @Test(expected = IllegalArgumentException::class)
    fun invalidProgressIsRejected() {
        calculatePredictiveBackTransform(
            progress = Float.NaN,
            swipeEdge = BackEventCompat.EDGE_LEFT
        )
    }
}
