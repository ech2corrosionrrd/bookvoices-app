package ua.nichnyk.listen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.WebDavSyncScheduler

/** Планувальник WebDAV: періодична робота й відкладена синхронізація після зупинки відтворення. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class WebDavSchedulerTest {

    private lateinit var context: Context
    private lateinit var wm: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        wm = WorkManager.getInstance(context)
    }

    private fun states(unique: String): List<WorkInfo.State> =
        wm.getWorkInfosForUniqueWork(unique).get().map { it.state }

    private val afterListen = "bookvoices_webdav_sync_after_listen"
    private val periodic = "bookvoices_webdav_sync"

    @Test
    fun enqueueAfterListeningSchedulesOneDelayedWork() {
        WebDavSyncScheduler.enqueueAfterListening(context)

        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(afterListen))
    }

    @Test
    fun repeatedPausesReplaceInsteadOfStacking() {
        repeat(3) { WebDavSyncScheduler.enqueueAfterListening(context) }

        val infos = wm.getWorkInfosForUniqueWork(afterListen).get()
        assertEquals(
            "REPLACE скасовує попередню роботу: живою лишається рівно одна",
            1,
            infos.count { it.state == WorkInfo.State.ENQUEUED },
        )
    }

    @Test
    fun cancelAfterListeningCancelsPendingWork() {
        WebDavSyncScheduler.enqueueAfterListening(context)

        WebDavSyncScheduler.cancelAfterListening(context)

        assertTrue(states(afterListen).all { it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun applyEnabledSchedulesPeriodicWork() {
        WebDavSyncScheduler.apply(context, enabled = true)

        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(periodic))
    }

    @Test
    fun applyDisabledCancelsBothPeriodicAndPendingAfterListenWork() {
        WebDavSyncScheduler.apply(context, enabled = true)
        WebDavSyncScheduler.enqueueAfterListening(context)

        WebDavSyncScheduler.apply(context, enabled = false)

        assertTrue(states(periodic).all { it == WorkInfo.State.CANCELLED })
        assertTrue(
            "вимкнення синхронізації не має лишати відкладений запит",
            states(afterListen).all { it == WorkInfo.State.CANCELLED },
        )
    }
}
