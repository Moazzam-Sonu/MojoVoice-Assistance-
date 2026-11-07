package com.mojo.app.core
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mojo.app.engine.MojoEngine

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MojoPrefs.autoStart(context)) {
            Log.d(TAG, "Boot completed — auto-start is off")
            return
        }
        Log.d(TAG, "Boot completed — starting MOJO")
        // Newer Android versions may refuse a microphone service started at boot;
        // MojoEngine handles that by stopping itself instead of crashing.
        try { MojoEngine.start(context) } catch (e: Exception) {
            Log.w(TAG, "Could not start MOJO at boot: ${e.message}")
        }
    }
    companion object { private const val TAG = "BootReceiver" }
}
