package ua.nichnyk.listen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.data.openForOverwrite

/**
 * «Зберегти у файл» через системний вибір місця (`ACTION_CREATE_DOCUMENT`) — пара до
 * [shareText] для бекапу, історії в CSV і каталогу.
 *
 * Навіщо: MIUI у горизонтальній орієнтації ховає у вікні «Поділитися» всі застосунки,
 * лишаючи «Друк» і трансляцію, і експорт нікуди не відправити (Redmi Note 11 Pro,
 * 13 листопада 2026). Системний вибір працює на будь-якій оболонці й зберігає і в
 * пам'ять телефона, і на диск хмари, без сторонніх файлових менеджерів.
 *
 * Текст готує [produce] уже **після** вибору місця, а не запам'ятовується перед запуском:
 * поворот екрана під час вибору перестворює Activity, і збережена наперед лямбда
 * загубилася б разом зі станом — файл лишився б порожнім.
 *
 * @param mimeType тип для системного вікна (`application/json`, `text/csv`, `text/markdown`).
 * @param produce вміст файла; викликається на IO.
 * @param onResult true — файл записано; false — не вдалося (вибір недоступний, помилка запису).
 *   Скасування вибору — не помилка: [onResult] тоді не викликається.
 * @return функція запуску: приймає запропоновану назву файла.
 */
@Composable
fun rememberSaveTextToFile(
    mimeType: String,
    produce: suspend () -> String,
    onResult: suspend (Boolean) -> Unit,
): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentProduce by rememberUpdatedState(produce)
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val text = currentProduce()
                    // Якщо обрали наявний файл — переписати, а не дописати в кінець.
                    val out = context.contentResolver.openForOverwrite(uri)
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.onFailure { AppLog.w("SaveToFile: не вдалося записати $mimeType", it) }.isSuccess
            currentOnResult(ok)
        }
    }
    return { name ->
        runCatching { launcher.launch(name) }.onFailure {
            // Немає системного вибору файлів (рідкісні збірки без DocumentsUI).
            AppLog.w("SaveToFile: системний вибір недоступний", it)
            scope.launch { currentOnResult(false) }
        }
    }
}
