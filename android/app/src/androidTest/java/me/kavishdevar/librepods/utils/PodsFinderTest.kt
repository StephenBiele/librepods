package me.kavishdevar.librepods.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behaviour of the RSSI filter behind the finder screen and the left-behind alert.
 *
 * `at` is passed explicitly everywhere: the trend compares a 3 second window against the
 * 3 seconds before it, so samples pushed at the same instant would never leave STEADY.
 */
@RunWith(AndroidJUnit4::class)
class PodsFinderTest {

    private val start = 1_000_000L

    @Before
    fun reset() {
        PodsFinder.reset()
    }

    /** Push [rssi] once per second for [count] seconds, starting at [fromSeconds]. */
    private fun stream(rssi: Int, fromSeconds: Int, count: Int) {
        repeat(count) { i ->
            PodsFinder.push(rssi, start + (fromSeconds + i) * 1_000L)
        }
    }

    @Test
    fun calibrationPointIsAboutOneMetre() {
        PodsFinder.push(-59, start)
        assertEquals(1.0, PodsFinder.sample.value!!.distanceMeters, 0.1)
    }

    @Test
    fun weakerSignalMeansFartherAway() {
        stream(-59, 0, 1)
        val near = PodsFinder.sample.value!!.distanceMeters
        stream(-85, 1, 8)
        val far = PodsFinder.sample.value!!.distanceMeters
        assertTrue("expected $far > $near", far > near)
    }

    @Test
    fun walkingCloserReportsCloser() {
        stream(-80, 0, 4)
        stream(-60, 4, 4)
        assertEquals(FinderTrend.CLOSER, PodsFinder.sample.value!!.trend)
    }

    @Test
    fun walkingAwayReportsFarther() {
        stream(-60, 0, 4)
        stream(-80, 4, 4)
        assertEquals(FinderTrend.FARTHER, PodsFinder.sample.value!!.trend)
    }

    @Test
    fun steadySignalReportsSteady() {
        stream(-70, 0, 8)
        assertEquals(FinderTrend.STEADY, PodsFinder.sample.value!!.trend)
    }

    @Test
    fun singleSpikeDoesNotClaimADirection() {
        stream(-70, 0, 7)
        PodsFinder.push(-40, start + 7_000L) // one loud advert
        stream(-70, 8, 2)
        assertEquals(FinderTrend.STEADY, PodsFinder.sample.value!!.trend)
    }

    @Test
    fun slowApproachIsStillDetected() {
        // 2 dB steps: too small for the old EMA-delta comparison, which needed 6 dB jumps.
        val steps = intArrayOf(-78, -76, -74, -72, -70, -68, -66)
        steps.forEachIndexed { i, rssi -> PodsFinder.push(rssi, start + i * 1_000L) }
        assertEquals(FinderTrend.CLOSER, PodsFinder.sample.value!!.trend)
    }

    @Test
    fun resetClearsSampleAndTrend() {
        stream(-80, 0, 4)
        stream(-60, 4, 4)
        assertNotNull(PodsFinder.sample.value)
        PodsFinder.reset()
        assertNull(PodsFinder.sample.value)
        // After a reset the first sample cannot inherit the previous trend.
        PodsFinder.push(-70, start + 60_000L)
        assertEquals(FinderTrend.STEADY, PodsFinder.sample.value!!.trend)
    }
}
