package com.stb6.spdf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderScalePolicyTest {
    @Test fun jitterDoesNotRepeatedlyReplaceTheRenderTarget() {
        val policy = RenderScalePolicy()
        assertEquals(1.8f, policy.update(1.8f, false), 0f)
        for (scale in listOf(1.81f, 1.79f, 1.82f, 1.8f)) {
            assertEquals(1.8f, policy.update(scale, true), 0f)
        }
    }

    @Test fun promotionAndReleaseUseDifferentThresholds() {
        val policy = RenderScalePolicy()
        policy.update(1.8f, false)
        assertEquals(2f, policy.update(2f, true), 0f)
        for (scale in listOf(1.99f, 2.01f, 1.7f, 1.5f)) {
            assertEquals(2f, policy.update(scale, true), 0f)
        }
        assertEquals(1f, policy.update(1.49f, true), 0f)
        assertEquals(1f, policy.update(1.9f, true), 0f)
        assertEquals(2f, policy.update(2f, true), 0f)
    }

    @Test fun releaseAlwaysRequestsTheFinalExactScale() {
        val policy = RenderScalePolicy()
        policy.update(1.8f, false)
        policy.update(2.2f, true)
        assertEquals(2.19f, policy.update(2.19f, false), 0f)
        assertEquals(2.19f, policy.update(2.2f, true), 0f)
        assertEquals(2.2f, policy.update(2.2f, false), 0f)
    }

    @Test fun largeChangesCrossMultipleAbsolutePowerOfTwoLevels() {
        val policy = RenderScalePolicy()
        policy.update(0.3f, false)
        assertEquals(4f, policy.update(7.9f, true), 0f)
        assertEquals(8f, policy.update(8f, true), 0f)
        assertEquals(0.25f, policy.update(0.3f, true), 0f)
    }

    @Test fun invalidLayoutScaleDoesNotPoisonTheNextGesture() {
        val policy = RenderScalePolicy()
        assertTrue(policy.update(Float.NaN, false).isNaN())
        assertTrue(policy.update(0f, true).isNaN())
        assertTrue(policy.update(Float.POSITIVE_INFINITY, true).isNaN())
        assertEquals(1.8f, policy.update(1.8f, true), 0f)
        assertEquals(2f, policy.update(2f, true), 0f)
    }
}
