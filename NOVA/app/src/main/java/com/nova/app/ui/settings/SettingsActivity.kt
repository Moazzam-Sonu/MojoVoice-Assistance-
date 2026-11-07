package com.nova.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nova.app.R
import com.nova.app.core.NovaApplication
import com.nova.app.ui.history.HistoryActivity
import com.nova.app.update.NovaUpdater
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var updater: NovaUpdater
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvUpdateBadge: TextView
    private lateinit var tvAboutVersion: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnDownloadUpdate: Button

    private var pendingRelease: NovaUpdater.ReleaseInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        updater = NovaUpdater(this)

        // Bind views
        tvCurrentVersion  = findViewById(R.id.tvCurrentVersion)
        tvUpdateBadge     = findViewById(R.id.tvUpdateBadge)
        tvAboutVersion    = findViewById(R.id.tvAboutVersion)
        btnCheckUpdate    = findViewById(R.id.btnCheckUpdate)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)

        // Show current version
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0.0" }

        tvCurrentVersion.text = "v$versionName"
        tvAboutVersion.text   = "Version $versionName"

        // Check update button
        btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        // Download + install update button
        btnDownloadUpdate.setOnClickListener {
            pendingRelease?.let { updater.showUpdateDialog(it) }
        }

        // History buttons
        findViewById<Button>(R.id.btnViewHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            lifecycleScope.launch {
                NovaApplication.instance.database.commandHistoryDao().deleteAll()
                Toast.makeText(this@SettingsActivity, "History cleared", Toast.LENGTH_SHORT).show()
            }
        }

        // Auto-check for update silently when Settings opens
        checkForUpdateSilent()
    }

    private fun checkForUpdate() {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "Checking..."
        tvUpdateBadge.text = "Checking..."
        tvUpdateBadge.setTextColor(getColor(R.color.text_secondary))

        lifecycleScope.launch {
            val release = updater.checkForUpdate()
            runOnUiThread {
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = "Check for update"
                when {
                    release == null -> {
                        tvUpdateBadge.text = "No internet or error"
                        tvUpdateBadge.setTextColor(getColor(R.color.text_muted))
                    }
                    release.isNewer -> {
                        pendingRelease = release
                        tvUpdateBadge.text = "Update available!"
                        tvUpdateBadge.setTextColor(getColor(R.color.nova_purple))
                        btnDownloadUpdate.visibility = View.VISIBLE
                        btnDownloadUpdate.text = "Download ${release.version}"
                        updater.showUpdateDialog(release)
                    }
                    else -> {
                        tvUpdateBadge.text = "Up to date"
                        tvUpdateBadge.setTextColor(getColor(R.color.nova_teal))
                        btnDownloadUpdate.visibility = View.GONE
                        Toast.makeText(this@SettingsActivity, "NOVA is up to date", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun checkForUpdateSilent() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate()
            if (release?.isNewer == true) {
                runOnUiThread {
                    pendingRelease = release
                    tvUpdateBadge.text = "Update available!"
                    tvUpdateBadge.setTextColor(getColor(R.color.nova_purple))
                    btnDownloadUpdate.visibility = View.VISIBLE
                    btnDownloadUpdate.text = "Download ${release.version}"
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}