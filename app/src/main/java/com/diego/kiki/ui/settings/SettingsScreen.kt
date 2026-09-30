package com.diego.kiki.ui.settings

import android.webkit.CookieManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diego.kiki.browser.SearchEngines

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isGlobalAdBlockEnabled: Boolean,
    bypassedSites: Set<String>,
    onToggleGlobalAdBlock: (Boolean) -> Unit,
    onRemoveBypassedSite: (String) -> Unit,
    onClearBrowsingData: (clearHistory: Boolean, clearCookies: Boolean, clearCache: Boolean, clearStorage: Boolean) -> Unit,
    appLockEnabled: Boolean,
    lockIncognitoOnly: Boolean,
    canUseBiometrics: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    onToggleLockIncognitoOnly: (Boolean) -> Unit,
    saveHistoryEnabled: Boolean,
    onToggleSaveHistory: (Boolean) -> Unit,
    searchEngineId: String,
    customSearchTemplate: String,
    onSetSearchEngine: (String) -> Unit,
    onSetCustomSearchTemplate: (String) -> Unit,
    themeMode: String,
    onSetThemeMode: (String) -> Unit,
    nightModeEnabled: Boolean,
    onSetNightMode: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearDataDialog by remember { mutableStateOf(false) }
    var blockThirdPartyCookies by remember { mutableStateOf(true) }
    var showCustomEngineDialog by remember { mutableStateOf(false) }
    var customTemplateInput by remember { mutableStateOf(customSearchTemplate) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                SectionHeader("Privacy & Protection")
            }

            // Global Ad Blocker Switch
            item {
                SettingsSwitchRow(
                    title = "Ad & Tracker Blocker",
                    subtitle = "Block ads and tracking scripts with EasyList",
                    icon = Icons.Default.Shield,
                    checked = isGlobalAdBlockEnabled,
                    onCheckedChange = onToggleGlobalAdBlock
                )
            }

            // 3rd-Party Cookies Switch
            item {
                SettingsSwitchRow(
                    title = "Block 3rd-Party Cookies",
                    subtitle = "Prevent cross-site tracking via cookies",
                    icon = Icons.Default.Lock,
                    checked = blockThirdPartyCookies,
                    onCheckedChange = { checked ->
                        blockThirdPartyCookies = checked
                        CookieManager.getInstance().setAcceptThirdPartyCookies(null, !checked)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Security")
            }

            // App Lock (biometric / device credential)
            item {
                SettingsSwitchRow(
                    title = "App Lock",
                    subtitle = if (canUseBiometrics) {
                        "Require biometrics or screen lock to open Kiki"
                    } else {
                        "No biometrics or screen lock found on this device"
                    },
                    icon = Icons.Default.Lock,
                    checked = appLockEnabled,
                    onCheckedChange = if (canUseBiometrics) onToggleAppLock else { _ -> }
                )
            }

            // Lock only incognito tabs
            if (appLockEnabled) {
                item {
                    SettingsSwitchRow(
                        title = "Lock Only Incognito Tabs",
                        subtitle = "The lock applies only while incognito tabs exist",
                        icon = Icons.Default.Shield,
                        checked = lockIncognitoOnly,
                        onCheckedChange = onToggleLockIncognitoOnly
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Privacy")
            }

            // Save browsing history toggle
            item {
                SettingsSwitchRow(
                    title = "Save Browsing History",
                    subtitle = "Record visited pages for the history screen and URL suggestions. Incognito tabs are never recorded.",
                    icon = Icons.Default.History,
                    checked = saveHistoryEnabled,
                    onCheckedChange = onToggleSaveHistory
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Search Engine")
            }

            items(SearchEngines.ALL) { engine ->
                SettingsRadioRow(
                    title = engine.name,
                    subtitle = if (engine.id == SearchEngines.CUSTOM.id) {
                        customSearchTemplate.ifBlank { "Set a URL template with %s" }
                    } else {
                        engine.template
                    },
                    selected = searchEngineId == engine.id,
                    onClick = {
                        if (engine.id == SearchEngines.CUSTOM.id) {
                            customTemplateInput = customSearchTemplate
                            showCustomEngineDialog = true
                        } else {
                            onSetSearchEngine(engine.id)
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Appearance")
            }

            items(listOf("system" to "Follow System", "light" to "Light", "dark" to "Dark")) { (mode, label) ->
                SettingsRadioRow(
                    title = label,
                    subtitle = null,
                    selected = themeMode == mode,
                    onClick = { onSetThemeMode(mode) }
                )
            }

            // Night mode (force-dark web content)
            item {
                SettingsSwitchRow(
                    title = "Night Mode for Web Pages",
                    subtitle = "Darken light websites; override per site in Site settings",
                    icon = Icons.Default.DarkMode,
                    checked = nightModeEnabled,
                    onCheckedChange = onSetNightMode
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Data & Storage")
            }

            // Clear Browsing Data Button
            item {
                SettingsClickableRow(
                    title = "Clear Browsing Data",
                    subtitle = "History, cookies, cache, and local web storage",
                    icon = Icons.Default.Delete,
                    onClick = { showClearDataDialog = true }
                )
            }

            if (bypassedSites.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionHeader("Whitelisted Sites (${bypassedSites.size})")
                }

                items(bypassedSites.toList()) { site ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = site,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveBypassedSite(site) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove site whitelist",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearDataDialog) {
        ClearBrowsingDataDialog(
            onConfirm = { history, cookies, cache, storage ->
                showClearDataDialog = false
                onClearBrowsingData(history, cookies, cache, storage)
            },
            onDismiss = { showClearDataDialog = false }
        )
    }

    if (showCustomEngineDialog) {
        AlertDialog(
            onDismissRequest = { showCustomEngineDialog = false },
            title = { Text("Custom Search Engine") },
            text = {
                Column {
                    Text(
                        text = "URL template. Use %s where the search query goes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customTemplateInput,
                        onValueChange = { customTemplateInput = it },
                        placeholder = { Text("https://example.com/search?q=%s") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCustomEngineDialog = false
                    if (customTemplateInput.isNotBlank()) {
                        onSetCustomSearchTemplate(customTemplateInput.trim())
                        onSetSearchEngine(SearchEngines.CUSTOM.id)
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomEngineDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsClickableRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ClearBrowsingDataDialog(
    onConfirm: (clearHistory: Boolean, clearCookies: Boolean, clearCache: Boolean, clearStorage: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(true) }
    var clearCache by remember { mutableStateOf(true) }
    var clearStorage by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Browsing Data") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clearHistory, onCheckedChange = { clearHistory = it })
                    Text("Browsing History")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clearCookies, onCheckedChange = { clearCookies = it })
                    Text("Cookies & Site Data")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clearCache, onCheckedChange = { clearCache = it })
                    Text("Cached Images & Files")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clearStorage, onCheckedChange = { clearStorage = it })
                    Text("DOM Web Storage")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(clearHistory, clearCookies, clearCache, clearStorage)
                }
            ) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
