package com.diego.kiki.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed preferences for security-sensitive settings. Falls back to
 * plain SharedPreferences on devices where the Keystore/ MasterKey init fails
 * (rare, e.g. corrupted keystore) so the browser stays usable — only booleans
 * and an int live here, nothing secret.
 */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kiki_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("kiki_secure_prefs_fallback", Context.MODE_PRIVATE)
    }

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()
        }

    /** When true, the lock only engages while the app contains incognito tabs. */
    var lockIncognitoOnly: Boolean
        get() = prefs.getBoolean(KEY_LOCK_INCOGNITO_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCK_INCOGNITO_ONLY, value).apply()
        }

    /** Seconds the app may stay backgrounded before locking again on resume. */
    var graceTimeoutSeconds: Int
        get() = prefs.getInt(KEY_GRACE_TIMEOUT, DEFAULT_GRACE_SECONDS)
        set(value) {
            prefs.edit().putInt(KEY_GRACE_TIMEOUT, value).apply()
        }

    var saveHistoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAVE_HISTORY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SAVE_HISTORY, value).apply()
        }

    /** One of SearchEngines.ALL ids ("google", "duckduckgo", "bing", "startpage", "custom"). */
    var searchEngineId: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, null) ?: "google"
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()
        }

    /** Template with %s placeholder; used when searchEngineId == "custom". */
    var customSearchTemplate: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_TEMPLATE, null) ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_SEARCH_TEMPLATE, value).apply()
        }

    /** One of "system", "light", "dark". */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, null) ?: "system"
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
        }

    /** Force-dark (CSS invert) for web content, overridable per site. */
    var nightModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply()
        }

    /** Last committed non-incognito page; restored in the initial tab on launch. */
    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, null) ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_URL, value).apply()
        }

    companion object {
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_LOCK_INCOGNITO_ONLY = "lock_incognito_only"
        private const val KEY_GRACE_TIMEOUT = "grace_timeout_seconds"
        private const val KEY_SAVE_HISTORY = "save_history_enabled"
        private const val KEY_SEARCH_ENGINE = "search_engine_id"
        private const val KEY_CUSTOM_SEARCH_TEMPLATE = "custom_search_template"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_NIGHT_MODE = "night_mode_enabled"
        private const val KEY_LAST_URL = "last_url"
        const val DEFAULT_GRACE_SECONDS = 60
    }
}
