package ua.nichnyk.listen.ui.screens

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportStateTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun secondImportIsRejectedWhileBusy() = runBlocking {
        val state = ImportState()
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        assertTrue(state.tryStart(scope) { runs.incrementAndGet(); gate.await() })
        assertTrue(state.busy.value)
        // «Другий ViewModel» бачить той самий стан і не стартує паралельний імпорт.
        assertFalse(state.tryStart(scope) { runs.incrementAndGet() })
        gate.complete(Unit)
        state.join()
        assertFalse(state.busy.value)
        assertEquals(1, runs.get())
        assertTrue(state.tryStart(scope) { runs.incrementAndGet() })
        assertEquals(2, runs.get())
    }

    @Test
    fun cancelWorksFromAnyObserver() = runBlocking {
        val state = ImportState()
        val gate = CompletableDeferred<Unit>()
        state.tryStart(scope) { gate.await() }
        state.cancel()
        state.join()
        assertTrue(state.userCancelled)
        assertFalse(state.busy.value)
    }

    @Test
    fun synchronousBodyLeavesStateClean() = runBlocking {
        val state = ImportState()
        assertTrue(state.tryStart(scope) { })
        assertFalse(state.busy.value)
        assertTrue(state.tryStart(scope) { })
    }
}