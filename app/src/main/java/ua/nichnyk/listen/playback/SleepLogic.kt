package ua.nichnyk.listen.playback

/**
 * Чиста логіка таймера сну: коли прокидатися й що показувати.
 *
 * Винесено з PlayerManager із тієї ж причини, що й SeekLogic: сам таймер живе поруч
 * із MediaController, а перевіряти треба саме арифметику.
 */

/**
 * Через скільки прокинутися, щоб оновити зворотний відлік.
 *
 * Крок — секунда замість колишніх 500 мс, і жодного разу довше, ніж лишилося.
 *
 * Спокуса прокидатися рівно на межі хвилини (бейдж показує саме хвилини) відкинута
 * свідомо: `sleepRemainingMs` — публічний стан, і робити його застарілим на цілу
 * хвилину означає підкласти міну під будь-який майбутній відлік із секундами.
 * Виграш був би мізерний — тікер відтворення й так працює кожні 400 мс.
 */
fun nextSleepTickDelay(remainingMs: Long, stepMs: Long = 1_000L, minStepMs: Long = 50L): Long {
    if (remainingMs <= minStepMs) return minStepMs
    return stepMs.coerceIn(minStepMs, remainingMs)
}

/**
 * Скільки хвилин показувати на значку таймера.
 *
 * Округлення вгору: «1» має світитися всю останню хвилину, а не зникати на 59-й секунді.
 */
fun sleepBadgeMinutes(remainingMs: Long, minuteMs: Long = 60_000L): Int {
    if (remainingMs <= 0L) return 0
    return ((remainingMs + minuteMs - 1) / minuteMs).toInt()
}

/**
 * Чи потрапляє [nowMinute] (хвилина доби, 0..1439) у нічне вікно [startMinute]..[endMinute].
 *
 * Вікно може переходити через північ (22:00–06:00). Однакові початок і кінець —
 * порожнє вікно, а не «цілодобово»: так випадково незаповнений розклад нічого не вмикає.
 * Початок входить у вікно, кінець — ні.
 */
fun isInNightWindow(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
    if (startMinute == endMinute) return false
    return if (startMinute < endMinute) {
        nowMinute in startMinute until endMinute
    } else {
        nowMinute >= startMinute || nowMinute < endMinute
    }
}

/**
 * Чи заводити нічний таймер сну, коли відтворення щойно почалося.
 *
 * Лише якщо ним ніхто не керує: заведений користувачем таймер (хоч у якому режимі)
 * важить більше за розклад і не перезаписується.
 */
fun shouldStartNightTimer(
    enabled: Boolean,
    timerActive: Boolean,
    nowMinute: Int,
    startMinute: Int,
    endMinute: Int,
): Boolean = enabled && !timerActive && isInNightWindow(nowMinute, startMinute, endMinute)
