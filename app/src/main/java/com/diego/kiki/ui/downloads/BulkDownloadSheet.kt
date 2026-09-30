package com.diego.kiki.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.kiki.download.BulkItem
import com.diego.kiki.download.MediaTypes

/**
 * Multi-select checklist of everything captured for the tab — used after an
 * auto-scroll pass ("smart stop") or from the media sheet's Download-all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDownloadSheet(
    items: List<BulkItem>,
    onDownload: (List<BulkItem>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(items) {
        mutableStateOf(items.map { it.url }.toSet())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Bulk download",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == items.size) emptySet() else items.map { it.url }.toSet()
            }) {
                Text(if (selected.size == items.size) "Deselect all" else "Select all")
            }
        }

        Text(
            text = if (items.any { it.type == MediaTypes.BLOB }) {
                "Blob videos need this page to stay open until done"
            } else {
                "HLS streams are merged to MP4 automatically"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(items, key = { it.url }) { item ->
                val checked = item.url in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - item.url else selected + item.url
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            selected = if (checked) selected - item.url else selected + item.url
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = MediaTypes.label(item.type),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = item.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item(key = "footer_spacer") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        Button(
            onClick = {
                onDownload(items.filter { it.url in selected })
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text("Download ${selected.size}")
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
