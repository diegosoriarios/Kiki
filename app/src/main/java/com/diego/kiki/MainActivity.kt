package com.diego.kiki

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.diego.kiki.ui.KikiViewModel
import com.diego.kiki.ui.browser.BrowserScreen
import com.diego.kiki.ui.theme.KikiTheme

class MainActivity : FragmentActivity() {

    private val viewModel: KikiViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * MediaProjection consent uses a direct startActivityForResult with an
     * explicit request code: FragmentActivity rejects the >16-bit codes the
     * ActivityResultRegistry generates ("Can only use lower 16 bits").
     */
    private companion object {
        const val SCREEN_RECORD_REQUEST = 4242
    }

    /** Launches the system MediaProjection consent dialog. */
    fun launchScreenRecordConsent() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_RECORD_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_RECORD_REQUEST && resultCode == RESULT_OK && data != null) {
            com.diego.kiki.screenrec.RecordingService.start(this, resultCode, data)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            viewModel.onAppForeground()
        }

        override fun onPause(owner: LifecycleOwner) {
            viewModel.onAppBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(lifecycleObserver)
        viewModel.evaluateColdStartLock()
        handleSharedUrl(intent)
        requestNotificationPermissionIfNeeded()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            KikiTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BrowserScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedUrl(intent)
    }

    /** Share-target: ACTION_SEND text/plain → open the URL in a new tab. */
    private fun handleSharedUrl(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = Regex("(https?://\\S+|www\\.\\S+)").find(text)?.value ?: return
            viewModel.openSharedUrl(url)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
