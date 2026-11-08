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

package me.kavishdevar.librepods.presentation.screens

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.services.AirPodsService

private const val WINDOW_MS = 120_000L

private data class HrPoint(val at: Long, val bpm: Int)

/** Apple-Health style accent, kept constant so the heart reads as "heart" in any theme. */
private val HeartRed = Color(0xFFFF2D55)

@Composable
fun HeartRateScreen() {
    val bpm by AirPodsService.heartRateBpm.collectAsState()
    val samples = remember { mutableStateListOf<HrPoint>() }
    val now = remember { mutableStateListOf(SystemClock.elapsedRealtime()) }

    // Sampling only matters while this page is up; ask for a fresh 1 Hz stream on entry.
    DisposableEffect(Unit) {
        AirPodsService.startHeartRateStream()
        onDispose { }
    }

    LaunchedEffect(bpm) {
        now[0] = SystemClock.elapsedRealtime()
        val value = bpm ?: return@LaunchedEffect
        val at = now[0]
        val last = samples.lastOrNull()
        if (last != null && last.bpm == value && at - last.at < 500) return@LaunchedEffect
        samples.add(HrPoint(at, value))
        while (samples.isNotEmpty() && at - samples.first().at > WINDOW_MS) samples.removeAt(0)
    }

    // Fixed window: the line grows in from the right and scrolls left, like a monitor.
    val windowStart = now[0] - WINDOW_MS
    val values = samples.map { it.bpm }
    val stats = if (values.isEmpty()) null else Triple(values.min(), values.average(), values.max())

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Big current reading, the way a heart-rate app leads.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "\u2764", fontSize = 34.sp, color = HeartRed)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = bpm?.toString() ?: "--",
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bpm == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.padding(top = 18.dp)) {
                    Text(text = "BPM", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (bpm == null) stringResource(R.string.heart_rate_waiting)
                        else stringResource(R.string.heart_rate_live),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bpm == null) MaterialTheme.colorScheme.onSurfaceVariant else HeartRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Trend: last two minutes, grid + area fill + line, "2 min ago → now" axis.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(12.dp)
            ) {
                if (samples.size < 2) {
                    Text(
                        text = stringResource(
                            if (bpm == null) R.string.heart_rate_empty_chart
                            else R.string.heart_rate_collecting
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val line = MaterialTheme.colorScheme.primary
                    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val maxBpm = (values.max() + 6).toFloat()
                        val minBpm = (values.min() - 6).coerceAtLeast(30).toFloat()
                        val span = (maxBpm - minBpm).coerceAtLeast(1f)
                        val spanMs = WINDOW_MS.toFloat()

                        fun yOf(v: Int): Float =
                            size.height - ((v - minBpm) / span) * size.height

                        fun xOf(at: Long): Float =
                            ((at - windowStart) / spanMs) * size.width

                        // horizontal grid
                        for (i in 0..3) {
                            val y = size.height * i / 3f
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                        }

                        val path = Path()
                        val area = Path()
                        samples.forEachIndexed { index, point ->
                            val x = xOf(point.at)
                            val y = yOf(point.bpm)
                            if (index == 0) {
                                path.moveTo(x, y)
                                area.moveTo(x, size.height)
                                area.lineTo(x, y)
                            } else {
                                path.lineTo(x, y)
                                area.lineTo(x, y)
                            }
                        }
                        area.lineTo(xOf(samples.last().at), size.height)
                        area.close()

                        drawPath(
                            path = area,
                            brush = Brush.verticalGradient(
                                listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))
                            )
                        )
                        drawPath(
                            path = path,
                            color = line,
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )
                        val lastPoint = samples.last()
                        drawCircle(
                            color = line,
                            radius = 5f,
                            center = Offset(xOf(lastPoint.at), yOf(lastPoint.bpm))
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2f,
                            center = Offset(xOf(lastPoint.at), yOf(lastPoint.bpm))
                        )
                        // y-axis extremes, so the line is readable without a legend
                        val paintMax = android.graphics.Paint().apply {
                            color = labelColor.toArgb()
                            textSize = 11.dp.toPx()
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            "${maxBpm.toInt()}",
                            4.dp.toPx(),
                            paintMax.textSize,
                            paintMax
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            "${minBpm.toInt()}",
                            4.dp.toPx(),
                            size.height - 4.dp.toPx(),
                            paintMax
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.heart_rate_axis_past),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.heart_rate_axis_now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (stats != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatBlock(stringResource(R.string.heart_rate_min), "${stats.first}")
                    StatBlock(stringResource(R.string.heart_rate_avg), "${stats.second.toInt()}")
                    StatBlock(stringResource(R.string.heart_rate_max), "${stats.third}")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.heart_rate_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
