package com.diego.kiki.ui.browser

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.kiki.browser.TabState

@Composable
fun BottomToolbar(
    tab: TabState?,
    totalTabsCount: Int,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onOpenTabsScreen: () -> Unit,
    onNewTab: (isIncognito: Boolean) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isBookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAutoScroll: () -> Unit = {},
    onToggleScreenRecord: () -> Unit = {},
    isScreenRecording: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button
            IconButton(
                onClick = onGoBack,
                enabled = tab?.canGoBack == true
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (tab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Forward Button
            IconButton(
                onClick = onGoForward,
                enabled = tab?.canGoForward == true
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (tab?.canGoForward == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Refresh / Stop Button
            IconButton(onClick = onRefreshOrStop) {
                Icon(
                    imageVector = if (tab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (tab?.isLoading == true) "Stop" else "Refresh"
                )
            }

            // Tabs Badge Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable(onClick = onOpenTabsScreen),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (totalTabsCount > 99) "99+" else totalTabsCount.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Menu Button
            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu"
                    )
                }

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("New Tab") },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onNewTab(false)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("New Incognito Tab") },
                        leadingIcon = {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onNewTab(true)
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Desktop Site") },
                        leadingIcon = {
                            Icon(Icons.Default.Computer, contentDescription = null)
                        },
                        trailingIcon = {
                            if (tab?.isDesktopMode == true) {
                                Icon(Icons.Default.Check, contentDescription = "Enabled")
                            }
                        },
                        onClick = {
                            isMenuExpanded = false
                            onToggleDesktopMode()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Find in Page") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onFindInPage()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(if (isBookmarked) "Remove Bookmark" else "Add Bookmark") },
                        leadingIcon = {
                            Icon(
                                if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onToggleBookmark()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        leadingIcon = {
                            Icon(Icons.Default.Star, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenBookmarks()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("History") },
                        leadingIcon = {
                            Icon(Icons.Default.History, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenHistory()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Auto-scroll") },
                        leadingIcon = {
                            Icon(Icons.Default.SwipeVertical, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenAutoScroll()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(if (isScreenRecording) "Stop Recording" else "Screen Record") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = if (isScreenRecording) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onToggleScreenRecord()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            val shareUrl = tab?.url
                            if (!shareUrl.isNullOrEmpty()) {
                                shareUrlIntent(context, shareUrl)
                            }
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Downloads") },
                        leadingIcon = {
                            Icon(Icons.Default.Download, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenDownloads()
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenSettings()
                        }
                    )
                }
            }
        }
    }
}

private fun shareUrlIntent(context: Context, url: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Share URL")
    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(shareIntent)
}
