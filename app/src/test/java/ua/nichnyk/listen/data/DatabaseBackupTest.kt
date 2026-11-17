package ua.nichnyk.listen.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseBackupTest {

    private fun dir(): File = Files.createTempDirectory("dbbak").toFile().also { it.deleteOnExit() }

    @Test
    fun copiesDbAndWalAtomically() {
        val d = dir()
        val db = File(d, "nichnyk.db").apply { writeBytes(ByteArray(100) { 1 }) }
        File(d, "nichnyk.db-wal").writeBytes(ByteArray(40) { 2 })
        val out = dir()
        assertTrue(ListenDatabase.backupDatabaseFiles(db, out))
        assertArrayEquals(ByteArray(100) { 1 }, File(out, "nichnyk.db.downgrade.bak").readBytes())
        assertArrayEquals(ByteArray(40) { 2 }, File(out, "nichnyk.db.downgrade.bak-wal").readBytes())
        assertEquals(setOf("nichnyk.db.downgrade.bak", "nichnyk.db.downgrade.bak-wal"), out.list()!!.toSet())
    }

    @Test
    fun notEnoughSpaceKeepsOldBackup() {
        val d = dir()
        val db = File(d, "nichnyk.db").apply { writeBytes(ByteArray(100) { 1 }) }
        val out = dir()
        val old = File(out, "nichnyk.db.downgrade.bak").apply { writeBytes(byteArrayOf(9)) }
        assertFalse(ListenDatabase.backupDatabaseFiles(db, out, usableSpace = 199))
        assertArrayEquals(byteArrayOf(9), old.readBytes())
        assertEquals(1, out.list()!!.size)
    }

    @Test
    fun failedCopyKeepsOldBackupAndCleansTmp() {
        val d = dir()
        val missing = File(d, "nichnyk.db") // файла немає: copyTo кине виняток
        val out = dir()
        val old = File(out, "nichnyk.db.downgrade.bak").apply { writeBytes(byteArrayOf(9)) }
        runCatching { ListenDatabase.backupDatabaseFiles(missing, out, usableSpace = Long.MAX_VALUE) }
        assertArrayEquals(byteArrayOf(9), old.readBytes())
        assertEquals(setOf("nichnyk.db.downgrade.bak"), out.list()!!.toSet())
    }

    @Test
    fun staleWalBackupRemovedWhenNoWalNow() {
        val d = dir()
        val db = File(d, "nichnyk.db").apply { writeBytes(ByteArray(70)) }
        val out = dir()
        File(out, "nichnyk.db.downgrade.bak-wal").writeBytes(byteArrayOf(7))
        assertTrue(ListenDatabase.backupDatabaseFiles(db, out))
        assertFalse(File(out, "nichnyk.db.downgrade.bak-wal").exists())
    }

    @Test
    fun effectiveVersionWithoutWalIsHeaderVersion() {
        val f = File.createTempFile("ver", ".db").apply { deleteOnExit() }
        val b = ByteArray(100); b[63] = 15
        f.writeBytes(b)
        assertEquals(15, ListenDatabase.readEffectiveVersion(f))
    }
}