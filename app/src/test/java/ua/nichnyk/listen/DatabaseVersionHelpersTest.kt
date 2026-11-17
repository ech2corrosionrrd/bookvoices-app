package ua.nichnyk.listen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.nichnyk.listen.data.ListenDatabase

/** Версія БД з заголовка файла, рішення «база новіша» та атомарна копія перед пониженням. */
class DatabaseVersionHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Мінімальний «заголовок SQLite»: user_version (big-endian) за зсувом 60. */
    private fun headerFile(name: String, version: Int, size: Int = 100): File {
        val f = tmp.newFile(name)
        val bytes = ByteArray(size)
        for (i in 0..3) bytes[60 + i] = (version shr (24 - 8 * i)).toByte()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun readUserVersion_readsBigEndianHeader() {
        assertEquals(12, ListenDatabase.readUserVersion(headerFile("a.db", 12)))
        assertEquals(300, ListenDatabase.readUserVersion(headerFile("b.db", 300)))
    }

    @Test
    fun readUserVersion_shortOrMissingFile_isMinusOne() {
        val short = tmp.newFile("short.db").apply { writeBytes(ByteArray(10)) }
        assertEquals(-1, ListenDatabase.readUserVersion(short))
        assertEquals(-1, ListenDatabase.readUserVersion(File(tmp.root, "nope.db")))
    }

    @Test
    fun readEffectiveVersion_withoutWal_usesHeaderOnly() {
        assertEquals(13, ListenDatabase.readEffectiveVersion(headerFile("c.db", 13)))
    }

    @Test
    fun readEffectiveVersion_emptyWal_usesHeaderOnly() {
        val db = headerFile("d.db", 11)
        File(db.path + "-wal").writeBytes(ByteArray(0))
        assertEquals(11, ListenDatabase.readEffectiveVersion(db))
    }

    @Test
    fun readEffectiveVersion_unreadableWithWal_assumesNewer() {
        // Непорожній WAL і файл, який SQLite не відкриє: вважаємо базу «можливо новішою».
        val db = headerFile("e.db", 1)
        File(db.path + "-wal").writeBytes(ByteArray(64) { 1 })
        assertEquals(Int.MAX_VALUE, ListenDatabase.readEffectiveVersion(db))
    }

    @Test
    fun backupDatabaseFiles_copiesDbAndWal() {
        val db = headerFile("f.db", 20)
        File(db.path + "-wal").writeBytes(ByteArray(8) { 7 })
        val dir = tmp.newFolder("bak1")
        assertTrue(ListenDatabase.backupDatabaseFiles(db, dir, usableSpace = Long.MAX_VALUE))
        val bak = File(dir, "nichnyk.db.downgrade.bak")
        assertTrue(bak.exists())
        assertEquals(db.length(), bak.length())
        assertEquals(8L, File(bak.path + "-wal").length())
        assertFalse(File(bak.path + ".tmp").exists())
    }

    @Test
    fun backupDatabaseFiles_notEnoughSpace_keepsOldBackup() {
        val db = headerFile("g.db", 20)
        val dir = tmp.newFolder("bak2")
        val bak = File(dir, "nichnyk.db.downgrade.bak").apply { writeText("old") }
        assertFalse(ListenDatabase.backupDatabaseFiles(db, dir, usableSpace = 10L))
        assertEquals("old", bak.readText())
    }

    @Test
    fun backupDatabaseFiles_withoutWal_removesStaleWalBackup() {
        val db = headerFile("h.db", 20)
        val dir = tmp.newFolder("bak3")
        val staleWal = File(dir, "nichnyk.db.downgrade.bak-wal").apply { writeText("stale") }
        assertTrue(ListenDatabase.backupDatabaseFiles(db, dir, usableSpace = Long.MAX_VALUE))
        assertFalse(staleWal.exists())
    }
}