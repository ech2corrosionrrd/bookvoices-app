package ua.nichnyk.listen.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ua.nichnyk.listen.data.ImportProgress

/**
 * Стан імпорту на рівні процесу (живе в AppContainer).
 *
 * Імпорт іде в процесному скоупі й переживає екран, а от `busy`, прогрес і сам Job
 * лежали у LibraryViewModel: після перестворення активності новий ViewModel бачив
 * `busy = false`, міг запустити другий імпорт паралельно з першим і не міг скасувати
 * перший. Тепер ViewModel лише спостерігає цей об'єкт.
 */
class ImportState {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Підсумки імпорту (успіх, дубль, скасування, помилка): їх бачить будь-який живий екран. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    var userCancelled = false
        private set

    fun reportProgress(progress: ImportProgress) {
        _progress.value = progress
    }

    fun post(message: String) {
        _messages.trySend(message)
    }

    /**
     * Запускає [body] у [scope], якщо інший імпорт не триває. `false` — зайнято.
     * Перевірка й позначка `busy` атомарні, тож два екрани не стартують удвох.
     */
    @Synchronized
    fun tryStart(scope: CoroutineScope, body: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        userCancelled = false
        _busy.value = true
        // LAZY: на Main.immediate корутина інакше могла б дійти до finally ще до
        // присвоєння `job`, і в полі лишився б завершений Job.
        val started = scope.launch(start = CoroutineStart.LAZY) {
            _progress.value = ImportProgress()
            try {
                body()
            } finally {
                _busy.value = false
                _progress.value = ImportProgress()
                job = null
            }
        }
        job = started
        started.start()
        return true
    }

    /** Скасовує поточний імпорт із будь-якого ViewModel. */
    fun cancel() {
        userCancelled = true
        job?.cancel()
    }

    suspend fun join() {
        job?.join()
    }
}