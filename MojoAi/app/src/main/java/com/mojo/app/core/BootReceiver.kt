package com.mojo.app.core
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mojo.app.engine.MojoEngine
import com.mojo.app.location.LocationProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — starting MOJO")
        // Newer Android versions may refuse a microphone service started at boot;
        // MojoEngine handles that by stopping itself instead of crashing.
        try { MojoEngine.start(context) } catch (e: Exception) {
            Log.w(TAG, "Could not start MOJO at boot: ${e.message}")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try { LocationProfileManager(context).reRegisterAllGeofences() } catch (_: Exception) {}
        }
    }
    companion object { private const val TAG = "BootReceiver" }
}
