package com.labteto.dshmobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.labteto.dshmobile.connection.KeepAliveWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Uses the application's real WorkManager registration, without selecting a host. */
@RunWith(AndroidJUnit4::class)
class KeepAliveSchedulingTest {
    @Test
    fun reschedulingUpdatesTheExistingPeriodicWorkWithConnectedNetworkConstraint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        suspend fun awaitWork(predicate: (WorkInfo) -> Boolean): WorkInfo = withTimeout(10_000) {
            var result: WorkInfo? = null
            while (result == null) {
                val active = workManager.getWorkInfosForUniqueWork(KeepAliveWorker.WORK_NAME)
                    .get(5, TimeUnit.SECONDS).filterNot { it.state.isFinished }
                if (active.size == 1 && predicate(active.single())) result = active.single()
                else delay(50)
            }
            result
        }

        KeepAliveWorker.schedule(context)
        val before = awaitWork { it.constraints.requiredNetworkType == NetworkType.CONNECTED }
        KeepAliveWorker.schedule(context)
        val after = awaitWork { it.generation > before.generation }

        assertEquals("UPDATE must retain the periodic work identity", before.id, after.id)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
    }
}
