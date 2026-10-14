package com.diego.kiki.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Floating action button showing the currently playing video's thumbnail
 * with a download icon overlay. Tapping it downloads the video directly.
 */
@Composable
fun PlayingVideoFab(
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            MediaThumbnail(
                model = posterUrl,
                icon = Icons.Default.Download,
                size = 64.dp
            )
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier.size(64.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download playing video",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.Center)
                    )
                }
            }
        }
    }
}
