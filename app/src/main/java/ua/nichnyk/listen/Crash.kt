package ua.nichnyk.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Обробник, який лишає слід замість того, щоб знімати процес.
 *
 * Корутина, запущена в scope із SupervisorJob **без** обробника, при винятку йде
 * прямо в дефолтний обробник потоку — тобто валить застосунок. У плеєрі це
 * найгірший можливий наслідок: збій запису налаштування чи одна недоступна книга
 * зупиняли б відтворення посеред глави.
 *
 * Тут навмисно немає ні перезапуску, ні відновлення: якщо операція не вдалася,
 * вона просто не вдалася. Завдання обробника — щоб решта застосунку про це не
 * дізналася падінням, а розробник дізнався через `adb logcat -s BookVoices`.
 */
fun crashSafeHandler(where: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error -> AppLog.w(where, error) }

/**
 * `viewModelScope.launch`, який не валить процес.
 *
 * Дії з екранів — перейменувати книгу, поставити мітку, записати налаштування —
 * не мають жодного способу повідомити про збій, окрім падіння: більшість із них
 * навіть не показує повідомлень. DataStore і Room кидають рідко, але кидають
 * (немає місця, зіпсований файл), і ціною такої рідкості був би вбитий застосунок.
 *
 * Там, де збій треба показати слухачеві, стоїть звичайний `launch` із власним
 * `runCatching` і повідомленням у `_messages` — цей помічник його не замінює.
 */
fun ViewModel.launchSafely(where: String, block: suspend () -> Unit): Job =
    viewModelScope.launch(crashSafeHandler(where)) { block() }
