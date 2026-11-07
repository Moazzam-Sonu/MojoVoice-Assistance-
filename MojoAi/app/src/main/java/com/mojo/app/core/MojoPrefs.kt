package com.mojo.app.core

import android.content.Context

/** Small user settings, stored in SharedPreferences. */
object MojoPrefs {
    private const val FILE = "mojo_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    fun autoStart(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_START, on).apply()
    }
}
