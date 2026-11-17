package ua.nichnyk.listen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.FolderBackup
import ua.nichnyk.listen.data.FolderBackupScheduler

/** Автокопія в теку: імена, прибирання старих копій і планування. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FolderBackupTest {

    @Test
    fun fileNameIsDatedAndSortable() {
        assertEquals("bookvoices-backup-2026-11-08.json", FolderBackup.fileName(LocalDate.of(2026, 11, 8)))
    }

    @Test
    fun pruneKeepsNewestAndNeverTouchesForeignFiles() {
        val ours = (1..10).map { FolderBackup.fileName(LocalDate.of(2026, 11, it)) }
        val foreign = listOf(
            "bookvoices-backup.json", // ручний експорт
            "notes.txt",
            "bookvoices-backup-2026-11-01.json.bak",
            "other-backup-2026-11-01.json",
        )
        val pruned = FolderBackup.filesToPrune((ours + foreign).shuffled(), keep = 7)
        assertEquals(
            listOf("2026-11-03", "2026-11-02", "2026-11-01").map { "bookvoices-backup-$it.json" },
            pruned,
        )
    }

    @Test
    fun nothingToPruneWithinLimit() {
        val names = (1..7).map { FolderBackup.fileName(LocalDate.of(2026, 11, it)) }
        assertTrue(FolderBackup.filesToPrune(names).isEmpty())
    }

    @Test
    fun schedulerEnablesAndCancelsDailyWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val wm = WorkManager.getInstance(context)

        FolderBackupScheduler.apply(context, enabled = true)
        FolderBackupScheduler.apply(context, enabled = true) // повторний виклик не дублює
        val active = wm.getWorkInfosForUniqueWork("bookvoices_folder_backup").get()
        assertEquals(1, active.size)
        assertEquals(WorkInfo.State.ENQUEUED, active.single().state)

        FolderBackupScheduler.apply(context, enabled = false)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("bookvoices_folder_backup").get().single().state)
    }
}
