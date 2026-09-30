package com.diego.kiki.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.diego.kiki.data.SiteSettingsRecord

/**
 * Per-site overrides for the host of the current tab. Tri-state choices:
 * Default (app-wide behavior), On, Off.
 */
@Composable
fun SiteSettingsDialog(
    host: String,
    initial: SiteSettingsRecord,
    onSave: (SiteSettingsRecord) -> Unit,
    onReset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var desktopMode by remember { mutableIntStateOf(initial.desktopMode) }
    var javaScript by remember { mutableIntStateOf(initial.javaScript) }
    var nightMode by remember { mutableIntStateOf(initial.nightMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Site settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                TriStateRow(
                    title = "Desktop site",
                    subtitle = "Desktop user agent for this site",
                    value = desktopMode,
                    onChange = { desktopMode = it }
                )
                Spacer(modifier = Modifier.height(12.dp))
                TriStateRow(
                    title = "JavaScript",
                    subtitle = "Off can break pages but saves data",
                    value = javaScript,
                    onChange = { javaScript = it }
                )
                Spacer(modifier = Modifier.height(12.dp))
                TriStateRow(
                    title = "Night mode",
                    subtitle = "Force-dark this site (overrides global)",
                    value = nightMode,
                    onChange = { nightMode = it }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        onReset(host)
                        onDismiss()
                    }) {
                        Text("Reset")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = {
                        onSave(
                            initial.copy(
                                desktopMode = desktopMode,
                                javaScript = javaScript,
                                nightMode = nightMode
                            )
                        )
                        onDismiss()
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun TriStateRow(
    title: String,
    subtitle: String,
    value: Int,
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = value == SiteSettingsRecord.DEFAULT,
                onClick = { onChange(SiteSettingsRecord.DEFAULT) },
                label = { Text("Default") }
            )
            FilterChip(
                selected = value == SiteSettingsRecord.ON,
                onClick = { onChange(SiteSettingsRecord.ON) },
                label = { Text("On") }
            )
            FilterChip(
                selected = value == SiteSettingsRecord.OFF,
                onClick = { onChange(SiteSettingsRecord.OFF) },
                label = { Text("Off") }
            )
        }
    }
}
