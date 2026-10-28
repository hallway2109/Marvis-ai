package com.marvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marvis.ai.device.AppForeground
import com.marvis.ai.device.MarvisOverlayService
import com.marvis.ai.device.ScreenCaptureService
import com.marvis.ai.ui.MarvisMainScreen
import com.marvis.ai.ui.MarvisTheme
import com.marvis.ai.ui.MarvisViewModel
import com.marvis.ai.ui.UiEvent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_ACTION = "marvis_action" }

    private val vm: MarvisViewModel by viewModels()
    private var thenListen = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) vm.onMicPermissionResult(true, thenListen)
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        ContextCompat.startForegroundService(this,
            Intent(this, ScreenCaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarvisRuntime.resume()                       // re-arm after a swipe-away
        enableEdgeToEdge()

        // Android 13+: notifications; every version: microphone. Must be granted BEFORE the mic service starts.
        val needed = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) { thenListen = false; permissions.launch(needed.toTypedArray()) }

        vm.restoreVoice()
        val app = application as MarvisApp
        if (Settings.canDrawOverlays(this) && app.settings.overlayOn.value) MarvisOverlayService.start(this)

        lifecycleScope.launch {
            vm.events.collect {
                when (it) {
                    UiEvent.RequestMic -> { thenListen = true; permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                    UiEvent.RequestProjection -> projection.launch(
                        (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
                }
            }
        }
        setContent {
            MarvisTheme {
                MarvisMainScreen(vm) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MarvisRuntime.resume()
        handleIntent(intent)
    }

    /** Actions sent by the floating orb's long-press menu. */
    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra(EXTRA_ACTION)) {
            "settings" -> vm.openSettings()
            "projection" -> vm.requestProjection()
        }
        i?.removeExtra(EXTRA_ACTION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        AppForeground.visible = true
        AppForeground.minimize = { moveTaskToBack(true) }
    }

    override fun onStop() { AppForeground.visible = false; super.onStop() }
    override fun onResume() { super.onResume(); vm.refreshA11y() }
}
