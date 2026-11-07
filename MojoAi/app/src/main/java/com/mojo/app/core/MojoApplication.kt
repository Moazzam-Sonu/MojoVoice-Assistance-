package com.mojo.app.core
import android.app.Application
import android.util.Log
import com.mojo.app.db.MojoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MojoApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val database: MojoDatabase by lazy { MojoDatabase.getInstance(this) }
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MOJO Application started")
    }
    companion object {
        private const val TAG = "MojoApplication"
        lateinit var instance: MojoApplication
            private set
    }
}