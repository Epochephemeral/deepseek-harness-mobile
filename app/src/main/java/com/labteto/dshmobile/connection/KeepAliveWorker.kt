package com.labteto.dshmobile.connection

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** Best-effort cold recovery. Android may deny foreground-service launch from periodic work. */
class KeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun connectionManager(): ConnectionManager
        fun hostsStore(): HostsStore
    }

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        if (!entry.hostsStore().settingsOnce().keepConnectedInBackground) return Result.success()
        // No setForeground(), alarm, or retry loop to evade FGS/time-budget restrictions. If the
        // platform rejects a service start the manager exposes START_RESTRICTED until foreground.
        entry.connectionManager().restoreBackgroundConnection()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "dsh-keep-alive"

        /**
         * Best-effort fallback using the separately persisted connection intent. WorkManager
         * timing is inexact; force-stop/Doze and foreground-service restrictions still apply.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
