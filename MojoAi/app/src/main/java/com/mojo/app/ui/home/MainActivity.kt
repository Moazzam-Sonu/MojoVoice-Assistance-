package com.mojo.app.ui.home
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mojo.app.accessibility.MojoAccessibilityService
import com.mojo.app.databinding.ActivityMainBinding
import com.mojo.app.engine.MojoEngine
import com.mojo.app.ui.settings.SettingsActivity
import com.mojo.app.ui.wakeword.WakeWordActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        if (hasMicPermission()) startMojo() else showPaused()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        val enabled = MojoAccessibilityService.instance != null
        binding.cardAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun setupUI() {
        binding.btnToggleMojo.setOnClickListener { if (isActive) stopMojo() else startMojo() }
        binding.btnWakeWord.setOnClickListener { startActivity(Intent(this, WakeWordActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnEnableAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startMojo() {
        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }
        MojoEngine.start(this)
        isActive = true
        binding.tvStatus.text = "MOJO is active"
        binding.btnToggleMojo.text = "Pause MOJO"
        binding.indicatorDot.setBackgroundResource(com.mojo.app.R.drawable.dot_green)
    }

    private fun stopMojo() {
        MojoEngine.stop(this)
        showPaused()
    }

    private fun showPaused() {
        isActive = false
        binding.tvStatus.text = "MOJO is paused"
        binding.btnToggleMojo.text = "Start MOJO"
        binding.indicatorDot.setBackgroundResource(com.mojo.app.R.drawable.dot_gray)
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
               Manifest.permission.READ_CONTACTS).forEach {
            if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) perms.add(it)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasMicPermission() && !isActive) startMojo()
    }

    companion object { private const val REQ_PERMS = 100 }
}
