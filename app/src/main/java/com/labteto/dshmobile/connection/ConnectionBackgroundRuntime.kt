package com.labteto.dshmobile.connection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Android side effects kept at one boundary; recovery tests never touch real services/work. */
@Singleton
open class ConnectionBackgroundRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    open fun updateWork(enabled: Boolean) {
        if (enabled) KeepAliveWorker.schedule(context) else KeepAliveWorker.cancel(context)
    }

    open fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
    }

    open fun stopService() {
        context.stopService(Intent(context, ConnectionService::class.java))
    }
}
