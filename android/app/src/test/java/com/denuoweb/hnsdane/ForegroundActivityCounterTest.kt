package com.denuoweb.hnsdane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundActivityCounterTest {
    @Test
    fun staysForegroundedAcrossInAppActivityNavigation() {
        var foregrounds = 0
        var backgrounds = 0
        val counter = ForegroundActivityCounter(
            onForeground = { foregrounds += 1 },
            onBackground = { backgrounds += 1 },
        )

        counter.activityStarted()
        assertTrue(counter.isForeground)
        counter.activityStarted()
        counter.activityStopped(isChangingConfigurations = false)

        assertEquals(1, foregrounds)
        assertEquals(0, backgrounds)

        counter.activityStopped(isChangingConfigurations = false)

        assertEquals(1, backgrounds)
        assertFalse(counter.isForeground)
    }

    @Test
    fun doesNotRestartForConfigurationChanges() {
        var foregrounds = 0
        var backgrounds = 0
        val counter = ForegroundActivityCounter(
            onForeground = { foregrounds += 1 },
            onBackground = { backgrounds += 1 },
        )

        counter.activityStarted()
        counter.activityStopped(isChangingConfigurations = true)
        assertTrue(counter.isForeground)
        counter.activityStarted()

        assertEquals(1, foregrounds)
        assertEquals(0, backgrounds)

        counter.activityStopped(isChangingConfigurations = false)

        assertEquals(1, backgrounds)
    }
}
