package com.diego.kiki.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadIntents
import com.diego.kiki.download.DownloadProgress
import com.diego.kiki.download.DownloadStorage
import com.diego.kiki.ui.KikiViewModel
import java.io.File
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    viewModel: KikiViewModel,
    onBack: () -> Unit
) {
    val records by viewModel.downloads.collectAsState()
    val activeProgress by viewModel.activeDownloadProgress.collectAsState()
    val context = LocalContext.current
    var showClearAllDialog by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    
    // Videos always on top (newest first); HLS streams listed beneath them
    val videoRecords = records.filter { it.downloadType != DownloadCoordinator.TYPE_HLS }
    val hlsRecords = records.filter { it.downloadType == DownloadCoordinator.TYPE_HLS }

    // Backfill local thumbnails for completed downloads that predate them
    LaunchedEffect(records) {
        records.forEach { record ->
            if (record.status == DownloadStatus.COMPLETED && record.thumbnailPath == null) {
                viewModel.backfillThumbnail(record)
            }
        }
    }

    // Exit selection mode when records change (e.g., after delete)
    LaunchedEffect(records.size) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = selectedIds.filter { id -> records.any { it.id == id } }.toSet()
            if (selectedIds.isEmpty()) {
                isSelectionMode = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (isSelectionMode) {
                // Selection mode top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit selection")
                    }
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    // Select All checkbox
                    val allSelected = selectedIds.size == records.size && records.isNotEmpty()
                    IconButton(onClick = {
                        selectedIds = if (allSelected) {
                            emptySet()
                        } else {
                            records.map { it.id }.toSet()
                        }
                    }) {
                        Icon(
                            imageVector = if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (allSelected) "Deselect all" else "Select all"
                        )
                    }
                    // Export selected
                    IconButton(
                        onClick = {
                            viewModel.exportDownloads(selectedIds)
                            Toast.makeText(context, "Exporting ${selectedIds.size} files", Toast.LENGTH_SHORT).show()
                            isSelectionMode = false
                            selectedIds = emptySet()
                        },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Export selected")
                    }
                    // Share selected
                    IconButton(
                        onClick = {
                            viewModel.shareDownloads(selectedIds)
                            isSelectionMode = false
                            selectedIds = emptySet()
                        },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share selected")
                    }
                    // Delete selected
                    IconButton(
                        onClick = { showDeleteSelectedDialog = true },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                }
            } else {
                // Normal mode top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Downloads",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all downloads")
                        }
                    }
                }
            }

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No downloads yet",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    listOf(videoRecords, hlsRecords).forEach { group ->
                        items(group, key = { it.id }) { record ->
                            DownloadRow(
                                record = record,
                                liveProgress = activeProgress[record.id],
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(record.id),
                                onToggleSelection = {
                                    selectedIds = if (selectedIds.contains(record.id)) {
                                        selectedIds - record.id
                                    } else {
                                        selectedIds + record.id
                                    }
                                    if (selectedIds.isNotEmpty() && !isSelectionMode) {
                                        isSelectionMode = true
                                    }
                                },
                                onLongPress = {
                                    isSelectionMode = true
                                    selectedIds = setOf(record.id)
                                },
                                onPause = { viewModel.pauseDownload(record.id) },
                                onResume = { viewModel.resumeDownload(record.id) },
                                onCancel = { viewModel.cancelDownload(record.id) },
                                onDelete = { viewModel.deleteDownload(record) },
                                onOpen = {
                                    val file = File(record.filePath)
                                    if (file.exists() && !DownloadIntents.openFile(
                                            context, file, record.mimeType
                                        )
                                    ) {
                                        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onShare = {
                                    val file = File(record.filePath)
                                    if (file.exists()) {
                                        DownloadIntents.shareFile(context, file, record.mimeType)
                                    }
                                },
                                onExport = { viewModel.exportDownload(record) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all downloads?") },
            text = {
                Text(
                    "All downloads will be removed from the app and their files deleted. " +
                            "Copies already exported to your device's Downloads folder are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    viewModel.clearAllDownloads()
                    Toast.makeText(context, "All downloads cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete ${selectedIds.size} downloads?") },
            text = {
                Text(
                    "Selected downloads will be removed from the app and their files deleted. " +
                            "Copies already exported to your device's Downloads folder are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloads(selectedIds)
                    Toast.makeText(context, "${selectedIds.size} downloads deleted", Toast.LENGTH_SHORT).show()
                    showDeleteSelectedDialog = false
                    isSelectionMode = false
                    selectedIds = emptySet()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    liveProgress: DownloadProgress?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit
) {
    val status = liveProgress?.status ?: record.status
    val downloaded = liveProgress?.downloadedBytes ?: record.downloadedBytes
    val total = liveProgress?.totalBytes ?: record.totalBytes
    val isHls = record.downloadType == DownloadCoordinator.TYPE_HLS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection()
                    } else if (status == DownloadStatus.COMPLETED) {
                        onOpen()
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) {
                        onLongPress()
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        
        MediaThumbnail(
            model = thumbnailModelFor(record),
            icon = iconFor(record),
            size = 56.dp
        )
        Spacer(modifier = Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val metaLine = listOfNotNull(
                record.qualityLabel?.takeIf { it.isNotBlank() },
                record.sourceHost?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val sizeText = if (isHls && total > 0) {
                "$downloaded / $total segments · ${statusLabel(status)}"
            } else {
                buildString {
                    append(formatBytes(downloaded))
                    if (total > 0 && status == DownloadStatus.DOWNLOADING) {
                        append(" / ")
                        append(formatBytes(total))
                    }
                    append(" · ")
                    append(statusLabel(status))
                }
            }
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (!record.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = record.errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (status == DownloadStatus.DOWNLOADING && total > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (status == DownloadStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (!isSelectionMode) {
            Spacer(modifier = Modifier.size(8.dp))

            Row {
                when (status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        RowAction(Icons.Default.Pause, "Pause", onPause)
                        RowAction(Icons.Default.Close, "Cancel", onCancel)
                    }
                    DownloadStatus.PAUSED -> {
                        RowAction(Icons.Default.PlayArrow, "Resume", onResume)
                        RowAction(Icons.Default.Close, "Cancel", onCancel)
                    }
                    DownloadStatus.FAILED -> {
                        RowAction(Icons.Default.PlayArrow, "Retry", onResume)
                        RowAction(Icons.Default.Delete, "Delete", onDelete)
                    }
                    DownloadStatus.COMPLETED -> {
                        RowAction(Icons.Default.OpenInNew, "Open", onOpen)
                        RowAction(Icons.Default.Share, "Share", onShare)
                        RowAction(Icons.Default.SaveAlt, "Export", onExport)
                        RowAction(Icons.Default.Delete, "Delete", onDelete)
                    }
                    DownloadStatus.CANCELLED -> {
                        RowAction(Icons.Default.Delete, "Delete", onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/**
 * Thumbnail model for a row: extracted local frame first, the file itself for
 * completed image downloads, then the remote poster captured at enqueue time.
 * Null → the row falls back to its type icon.
 */
private fun thumbnailModelFor(record: DownloadRecord): Any? {
    record.thumbnailPath?.let { return java.io.File(it) }
    val ext = DownloadStorage.fileExtensionOf(record.fileName)
    if (record.status == DownloadStatus.COMPLETED &&
        record.filePath.isNotBlank() &&
        ext in IMAGE_EXTENSIONS
    ) {
        return java.io.File(record.filePath)
    }
    return record.thumbnailUrl
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

private fun iconFor(record: DownloadRecord): androidx.compose.ui.graphics.vector.ImageVector {
    return when (DownloadStorage.fileExtensionOf(record.fileName)) {
        "mp4", "webm", "mkv", "mov", "3gp", "avi", "ts" -> Icons.Default.Videocam
        "mp3", "m4a", "ogg", "wav", "aac", "flac" -> Icons.Default.MusicNote
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> Icons.Default.Image
        "pdf", "zip", "apk", "doc", "docx", "txt", "epub" -> Icons.Default.Description
        else -> Icons.Default.Download
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
