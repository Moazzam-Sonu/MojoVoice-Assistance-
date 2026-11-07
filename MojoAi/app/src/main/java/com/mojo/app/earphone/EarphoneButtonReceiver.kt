package com.mojo.app.earphone
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

class EarphoneButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        abortBroadcast()
        context.sendBroadcast(Intent("com.mojo.app.EARPHONE_WAKE"))
    }
}