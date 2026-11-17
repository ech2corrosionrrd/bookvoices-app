package ua.nichnyk.listen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedGrantLimitTest {

    @Test
    fun limitDependsOnAndroidVersion() {
        assertEquals(128, AudioImporter.persistedGrantLimit(29))
        assertEquals(512, AudioImporter.persistedGrantLimit(30))
        assertEquals(512, AudioImporter.persistedGrantLimit(35))
    }

    @Test
    fun staysAwayFromLimit() {
        assertTrue(AudioImporter.persistedGrantsHaveRoom(0, 29))
        assertTrue(AudioImporter.persistedGrantsHaveRoom(119, 29))
        assertFalse(AudioImporter.persistedGrantsHaveRoom(120, 29))
        assertTrue(AudioImporter.persistedGrantsHaveRoom(503, 34))
        assertFalse(AudioImporter.persistedGrantsHaveRoom(504, 34))
    }
}
