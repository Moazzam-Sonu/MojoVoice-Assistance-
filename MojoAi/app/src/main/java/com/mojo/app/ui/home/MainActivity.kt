package com.mojo.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mojo.app.accessibility.MojoAccessibilityService
import com.mojo.app.core.MojoStatus
import com.mojo.app.engine.MojoEngine
import com.mojo.app.ui.settings.SettingsActivity
import com.mojo.app.ui.theme.MojoTheme
import com.mojo.app.ui.wakeword.WakeWordActivity
import com.mojo.app.engine.wake.MojoWakeWordEngine

class MainActivity : ComponentActivity() {

    private var accessibilityOn by mutableStateOf(false)
    private var wakeTrained by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(true)

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMicPermission() && !MojoStatus.running.value) MojoEngine.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MojoTheme {
                val running by MojoStatus.running.collectAsState()
                val state by MojoStatus.state.collectAsState()
                val heard by MojoStatus.heard.collectAsState()
                val reply by MojoStatus.reply.collectAsState()
                HomeScreen(
                    running = running,
                    state = state,
                    heard = heard,
                    reply = reply,
                    wakeTrained = wakeTrained,
                    accessibilityOn = accessibilityOn,
                    batteryUnrestricted = batteryUnrestricted,
                    onToggle = { if (running) MojoEngine.stop(this) else startMojo() },
                    onWakeWord = { startActivity(Intent(this, WakeWordActivity::class.java)) },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onBattery = { askBatteryExemption() },
                )
            }
        }
        if (hasMicPermission()) MojoEngine.start(this)
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        accessibilityOn = MojoAccessibilityService.instance != null
        wakeTrained = MojoWakeWordEngine.isTrained(this)
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    /** Asks Android to stop battery optimisation from killing MOJO's listener. */
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startMojo() {
        if (hasMicPermission()) MojoEngine.start(this) else requestMissingPermissions()
    }

    private fun requestMissingPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }
}
