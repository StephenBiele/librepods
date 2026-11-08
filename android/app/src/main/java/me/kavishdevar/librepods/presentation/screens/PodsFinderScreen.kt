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

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.utils.FinderTrend
import me.kavishdevar.librepods.utils.PodsFinder

/**
 * Warm/colder finder driven purely by the RSSI of the AirPods' BLE adverts.
 * No Apple account, no root: it is the same signal a "Pods Finder" style app uses.
 */
@Composable
fun PodsFinderScreen() {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    val sample by PodsFinder.sample.collectAsState()

    // Fresher RSSI only while this screen is on: ~10 reports/s instead of one per 500 ms,
    // restored the moment the user leaves.
    DisposableEffect(Unit) {
        AirPodsService.setFinderFastScan(true)
        onDispose { AirPodsService.setFinderFastScan(false) }
    }
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding =
        if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding =
        if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 12.dp

    // Re-render the "last seen" line even when no new advert arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Adverts stop when the pods sleep or leave; without a freshness cut-off the last
    // distance would sit on screen looking current forever.
    val fresh = sample?.takeIf { now - it.at < STALE_AFTER_MS }
    val lastSeenText = sample?.let { relativeTime(now - it.at) }
    val distanceText = fresh?.let {
        if (it.distanceMeters < 10.0) {
            String.format("%.1f m", it.distanceMeters)
        } else {
            String.format("%.0f m", it.distanceMeters)
        }
    }
    val trendText = when (fresh?.trend) {
        FinderTrend.CLOSER -> stringResource(R.string.pods_finder_closer)
        FinderTrend.FARTHER -> stringResource(R.string.pods_finder_farther)
        FinderTrend.STEADY -> stringResource(R.string.pods_finder_steady)
        null -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.pods_finder_signal),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = distanceText ?: stringResource(R.string.pods_finder_waiting),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (trendText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = trendText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val fraction = fresh?.signalFraction ?: 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fresh?.let { String.format("%d dBm", it.rssi) }
                            ?: stringResource(R.string.pods_finder_no_signal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lastSeenText != null) {
                        Text(
                            text = stringResource(R.string.pods_finder_last_seen, lastSeenText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.pods_finder_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        StyledToggle(
            label = stringResource(R.string.left_behind_alerts),
            description = stringResource(R.string.left_behind_alerts_description),
            checked = sharedPreferences.getBoolean("left_behind_alerts", false),
            onCheckedChange = { enabled ->
                sharedPreferences.edit { putBoolean("left_behind_alerts", enabled) }
                now = System.currentTimeMillis()
            }
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

// Long enough to ride out a few missed adverts, short enough that a stale reading
// never looks like a live one.
private const val STALE_AFTER_MS = 15_000L

private fun relativeTime(ageMs: Long): String = when {
    ageMs < 2_000 -> "just now"
    ageMs < 60_000 -> "${ageMs / 1_000}s ago"
    ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
    else -> "${ageMs / 3_600_000}h ago"
}
