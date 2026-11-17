package ua.nichnyk.listen

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.nichnyk.listen.data.TruncatingOutputStream

/**
 * Запасний шлях [ua.nichnyk.listen.data.openForOverwrite] — для провайдерів без `"wt"`:
 * файл відкрито як `"rw"`, тож без скорочення коротша копія лишила б хвіст старої.
 */
class SafWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Як `"rw"` у SAF: дескриптор із позицією 0 і без скорочення. */
    private fun openRw(file: File): Pair<RandomAccessFile, FileOutputStream> {
        val raf = RandomAccessFile(file, "rw")
        return raf to FileOutputStream(raf.fd)
    }

    @Test
    fun shorterContentLeavesNoTailOfLongerFile() {
        val file = tmp.newFile("bookvoices-backup.json").apply { writeText("{\"old\":\"" + "x".repeat(500) + "\"}") }
        val (raf, fos) = openRw(file)
        TruncatingOutputStream(fos).use { it.write("{\"new\":1}".toByteArray()) }
        raf.close()
        assertEquals("{\"new\":1}", file.readText())
    }

    @Test
    fun arrayIsWrittenInBlocksNotByteByByte() {
        val file = tmp.newFile("big.json")
        var singleByteWrites = 0
        val raf = RandomAccessFile(file, "rw")
        val counting = object : FileOutputStream(raf.fd) {
            override fun write(b: Int) {
                singleByteWrites++
                super.write(b)
            }
        }
        val payload = ByteArray(256 * 1024) { 'a'.code.toByte() }
        TruncatingOutputStream(counting).use { it.write(payload) }
        raf.close()
        assertEquals("FilterOutputStream не має розбивати масив на байти", 0, singleByteWrites)
        assertEquals(payload.size.toLong(), file.length())
    }
}
