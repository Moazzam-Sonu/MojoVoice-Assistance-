package com.nova.app.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * NovaUpdater — checks GitHub Releases for a newer APK and
 * downloads + installs it in the background. User taps one button.
 * No Play Store needed.
 */
class NovaUpdater(private val activity: Activity) {

    data class ReleaseInfo(
        val version: String,
        val apkUrl: String,
        val apkName: String,
        val releaseNotes: String,
        val isNewer: Boolean
    )

    // ── Check for update ──

    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL(RELEASES_API)
            val conn = url.openConnection().apply {
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout    = 8000
            }
            val json = conn.getInputStream().bufferedReader().readText()
            val obj  = JSONObject(json)

            val latestTag  = obj.optString("tag_name", "")
            val latestName = obj.optString("name", latestTag)
            val body       = obj.optString("body", "")
            val assets     = obj.optJSONArray("assets")

            if (assets == null || assets.length() == 0) return@withContext null

            val apkAsset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return@withContext null

            val apkUrl  = apkAsset.optString("browser_download_url")
            val apkName = apkAsset.optString("name")

            val currentCode  = activity.packageManager
                .getPackageInfo(activity.packageName, 0).versionCode
            val latestCode   = extractVersionCode(latestTag)
            val isNewer      = latestCode > currentCode

            ReleaseInfo(latestName, apkUrl, apkName, body, isNewer)
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    // ── Show update dialog ──

    fun showUpdateDialog(release: ReleaseInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Update available — ${release.version}")
            .setMessage(
                "A new version of NOVA is available.\n\n" +
                release.releaseNotes.take(300).let { if (it.length == 300) "$it..." else it } +
                "\n\nDownload and install now?"
            )
            .setPositiveButton("Update now") { _, _ ->
                downloadAndInstall(release)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ── Download APK in background ──

    private fun downloadAndInstall(release: ReleaseInfo) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Remove old downloaded APK if exists
        val apkFile = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), release.apkName)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(release.apkUrl)).apply {
            setTitle("NOVA Update")
            setDescription("Downloading ${release.version}...")
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, release.apkName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val downloadId = dm.enqueue(request)
        Log.d(TAG, "Download started: id=$downloadId url=${release.apkUrl}")

        // Listen for download complete
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)
                    installApk(apkFile)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    // ── Install downloaded APK ──

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found after download")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Log.d(TAG, "Install prompt shown for: ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}")
        }
    }

    // ── Version parsing ──

    private fun extractVersionCode(tag: String): Int {
        return try {
            tag.replace("v", "").replace(".", "")
                .filter { it.isDigit() }.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
    }

    companion object {
        private const val TAG          = "NovaUpdater"
        private const val RELEASES_API = "https://api.github.com/repos/Aniketsingh2104/NOVA/releases/latest"
    }
}