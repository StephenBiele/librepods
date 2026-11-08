/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/** Whether the signal strength has risen, fallen or held steady over the trend window. */
enum class FinderTrend { CLOSER, FARTHER, STEADY }

data class FinderSample(
    /** Raw RSSI of the last advert, dBm. */
    val rssi: Int,
    /** Exponentially smoothed RSSI, dBm. */
    val smoothed: Float,
    /** Rough distance in metres from the log-distance path loss model. */
    val distanceMeters: Double,
    val trend: FinderTrend,
    /** Wall clock of the advert this sample came from. */
    val at: Long
) {
    /** 0..1 meter fill for the UI, mapping -100 dBm..-40 dBm. */
    val signalFraction: Float
        get() = ((smoothed + 100f) / 60f).coerceIn(0f, 1f)
}

/**
 * RSSI filter behind the "find my AirPods" screen and the left-behind alert.
 *
 * The EMA drives the distance readout, because that only needs a stable centre of mass.
 * The trend does NOT compare EMA values: with alpha 0.25 a change of d in the smoothed
 * output requires 4d in the raw reading, so comparing consecutive EMA outputs made slow
 * walking look "steady" while a single loud advert looked like movement.
 *
 * Trend instead compares the *median* raw RSSI of the last [TREND_WINDOW_MS] against the
 * median of the window before it. Medians reject the single-sample spikes that RSSI is full
 * of, and comparing two windows rather than two adjacent samples removes the damping problem.
 * Two consecutive agreeing calculations are required before the reported trend changes.
 */
object PodsFinder {
    private const val ALPHA = 0.25f
    private const val RSSI_AT_ONE_METRE = -59.0
    private const val PATH_LOSS_EXPONENT = 2.4
    private const val TREND_WINDOW_MS = 3_000L
    private const val TREND_THRESHOLD_DB = 3.0
    private const val CONFIRM_SAMPLES = 2
    private const val MAX_HISTORY = 64

    private data class Point(val at: Long, val rssi: Int)

    private val _sample = MutableStateFlow<FinderSample?>(null)
    val sample = _sample.asStateFlow()

    private val history = ArrayDeque<Point>()
    private var smoothed: Float? = null
    private var reported: FinderTrend = FinderTrend.STEADY
    private var candidate: FinderTrend = FinderTrend.STEADY
    private var candidateCount = 0

    @Synchronized
    fun push(rssi: Int, at: Long = System.currentTimeMillis()) {
        val previous = smoothed
        val value = if (previous == null) rssi.toFloat() else previous + ALPHA * (rssi - previous)
        smoothed = value

        history.addLast(Point(at, rssi))
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
        while (history.size > 2 && at - history.first().at > 2 * TREND_WINDOW_MS) {
            history.removeFirst()
        }

        val current = medianOf { age -> age <= TREND_WINDOW_MS }
        val previousWindow = medianOf { age -> age in (TREND_WINDOW_MS + 1)..(2 * TREND_WINDOW_MS) }

        val raw = when {
            current == null || previousWindow == null -> FinderTrend.STEADY
            current - previousWindow > TREND_THRESHOLD_DB -> FinderTrend.CLOSER
            previousWindow - current > TREND_THRESHOLD_DB -> FinderTrend.FARTHER
            else -> FinderTrend.STEADY
        }

        if (raw == reported) {
            candidate = raw
            candidateCount = 0
        } else if (raw == candidate) {
            candidateCount++
            if (candidateCount >= CONFIRM_SAMPLES) {
                reported = candidate
                candidateCount = 0
            }
        } else {
            candidate = raw
            candidateCount = if (raw == FinderTrend.STEADY) 0 else 1
        }

        _sample.value = FinderSample(
            rssi = rssi,
            smoothed = value,
            distanceMeters = distanceFor(value),
            trend = reported,
            at = at
        )
    }

    /** Median raw RSSI of the samples whose age at `now` satisfies [ageFilter]. */
    private fun medianOf(ageFilter: (Long) -> Boolean): Double? {
        val now = history.last().at
        val values = history
            .filter { ageFilter(now - it.at) }
            .map { it.rssi }
            .sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) {
            values[middle].toDouble()
        } else {
            (values[middle - 1] + values[middle]) / 2.0
        }
    }

    fun distanceFor(smoothedRssi: Float): Double =
        10.0.pow((RSSI_AT_ONE_METRE - smoothedRssi) / (10.0 * PATH_LOSS_EXPONENT))

    @Synchronized
    fun reset() {
        smoothed = null
        history.clear()
        reported = FinderTrend.STEADY
        candidate = FinderTrend.STEADY
        candidateCount = 0
        _sample.value = null
    }
}
