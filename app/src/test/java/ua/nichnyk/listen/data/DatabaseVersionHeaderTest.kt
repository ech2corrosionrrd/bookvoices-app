package ua.nichnyk.listen.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseVersionHeaderTest {

    private fun dbFile(version: Int, size: Int = 100): File {
        val f = File.createTempFile("hdr", ".db")
        f.deleteOnExit()
        val bytes = ByteArray(size)
        if (size >= 64) {
            bytes[60] = (version ushr 24).toByte()
            bytes[61] = (version ushr 16).toByte()
            bytes[62] = (version ushr 8).toByte()
            bytes[63] = version.toByte()
        }
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun readsUserVersionFromHeader() {
        assertEquals(12, ListenDatabase.readUserVersion(dbFile(12)))
        assertEquals(300, ListenDatabase.readUserVersion(dbFile(300)))
    }

    @Test
    fun tooShortOrMissingFileGivesMinusOne() {
        assertEquals(-1, ListenDatabase.readUserVersion(dbFile(0, size = 10)))
        assertEquals(-1, ListenDatabase.readUserVersion(File("no-such-file.db")))
    }
}
