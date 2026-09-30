package com.diego.kiki.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diego.kiki.automation.AutoScroller

private val INTERVAL_CHOICES = listOf(500L to "0.5s", 1000L to "1s", 2000L to "2s", 3000L to "3s")

@Composable
fun AutoScrollPanel(
    state: AutoScroller.State,
    onSelectInterval: (Long) -> Unit,
    onBeginSetup: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    mediaCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Auto-scroll",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_CHOICES.forEach { (ms, label) ->
                    FilterChip(
                        selected = state.intervalMs == ms,
                        onClick = { onSelectInterval(ms) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (state.running) {
                Text(
                    text = "Swipes: ${state.swipeCount} · Media found: $mediaCount",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("Stop")
                }
            } else {
                Text(
                    text = "Place two pins to define a swipe — the app repeats it automatically until stopped. Works on feeds and galleries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onBeginSetup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SwipeVertical, contentDescription = null)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("Place swipe pins…")
                }
            }
        }
    }
}
