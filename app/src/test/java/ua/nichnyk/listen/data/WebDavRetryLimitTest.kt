package ua.nichnyk.listen.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ліміт повторів воркера WebDAV-синхронізації. */
class WebDavRetryLimitTest {

    @Test
    fun belowLimitStillRetries() {
        assertFalse(webDavRetriesExhausted(0))
        assertFalse(webDavRetriesExhausted(WEBDAV_MAX_ATTEMPTS - 1))
    }

    @Test
    fun atLimitGivesUp() {
        assertTrue(webDavRetriesExhausted(WEBDAV_MAX_ATTEMPTS))
        assertTrue(webDavRetriesExhausted(WEBDAV_MAX_ATTEMPTS + 3))
    }
}