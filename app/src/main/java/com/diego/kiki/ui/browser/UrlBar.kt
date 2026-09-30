package com.diego.kiki.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diego.kiki.browser.TabState
import com.diego.kiki.browser.UrlUtils
import com.diego.kiki.data.HistoryRecord

private val QUICK_SUGGESTIONS = listOf(
    "google.com" to "https://www.google.com",
    "youtube.com" to "https://www.youtube.com",
    "reddit.com" to "https://www.reddit.com",
    "duckduckgo.com" to "https://www.duckduckgo.com",
    "wikipedia.org" to "https://www.wikipedia.org"
)

@Composable
fun UrlBar(
    tab: TabState?,
    onGoToUrl: (String) -> Unit,
    onSearch: (String) -> Unit,
    onQueryChanged: (String) -> Unit = {},
    historySuggestions: List<HistoryRecord> = emptyList(),
    onOpenShield: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Back gesture / keyboard dismissal exits edit mode
    BackHandler(enabled = isEditing) {
        isEditing = false
        inputText = TextFieldValue("")
        onQueryChanged("")
    }

    fun exitEditing() {
        isEditing = false
        inputText = TextFieldValue("")
        onQueryChanged("")
        keyboardController?.hide()
    }

    val currentUrl = tab?.url ?: ""
    val isHttps = currentUrl.startsWith("https://", ignoreCase = true)

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Security / Incognito Icon
                    if (tab?.isIncognito == true) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "Incognito",
                            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (isHttps) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Secure",
                            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    } else if (currentUrl.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Not secure",
                            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    if (!isEditing) {
                        // Display URL Box
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    // Select the whole URL so typing replaces it
                                    inputText = TextFieldValue(
                                        currentUrl,
                                        selection = TextRange(0, currentUrl.length)
                                    )
                                    onQueryChanged(currentUrl)
                                    isEditing = true
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = if (currentUrl.isNotEmpty()) UrlUtils.getDisplayHost(currentUrl) else "Search or type URL",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (currentUrl.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // Editable TextField
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }

                        TextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                onQueryChanged(it.text)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .focusRequester(focusRequester),
                            placeholder = {
                                Text("Search or type web address")
                            },
                            singleLine = true,
                            trailingIcon = {
                                if (inputText.text.isNotEmpty()) {
                                    IconButton(onClick = {
                                        inputText = TextFieldValue("")
                                        onQueryChanged("")
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear input"
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val text = inputText.text
                                    exitEditing()
                                    onGoToUrl(text)
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                    }

                    // Shield / Protection icon
                    IconButton(onClick = onOpenShield) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Protection Shield",
                            tint = if ((tab?.blockedAdsCount ?: 0) > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Page Loading Progress Bar
                if (tab?.isLoading == true) {
                    LinearProgressIndicator(
                        progress = { (tab.progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Suggestions Dropdown when editing
        AnimatedVisibility(visible = isEditing) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    if (inputText.text.isNotBlank()) {
                        item {
                            SuggestionRow(
                                title = "Search: \"${inputText.text}\"",
                                subtitle = null,
                                icon = Icons.Default.Search,
                                onClick = {
                                    val text = inputText.text
                                    exitEditing()
                                    onSearch(text)
                                }
                            )
                        }
                    }

                    items(historySuggestions, key = { "history_" + it.url }) { record ->
                        SuggestionRow(
                            title = record.title.ifBlank { record.url },
                            subtitle = record.url,
                            icon = Icons.Default.History,
                            onClick = {
                                exitEditing()
                                onGoToUrl(record.url)
                            }
                        )
                    }

                    items(filteredQuickSuggestions(inputText.text), key = { "quick_" + it.second }) { (label, url) ->
                        SuggestionRow(
                            title = label,
                            subtitle = url,
                            icon = Icons.Default.Language,
                            onClick = {
                                exitEditing()
                                onGoToUrl(url)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun filteredQuickSuggestions(query: String): List<Pair<String, String>> {
    return if (query.isBlank()) {
        QUICK_SUGGESTIONS
    } else {
        QUICK_SUGGESTIONS.filter { (label, url) ->
            label.contains(query, ignoreCase = true) || url.contains(query, ignoreCase = true)
        }
    }
}
