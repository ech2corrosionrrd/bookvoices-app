package ua.nichnyk.listen.data

/**
 * Порядок глав: імена файлів чи номер доріжки в тегах.
 *
 * Звичайно файли називають за номерами («01 Вступ.mp3»), і природне сортування імен дає
 * правильний порядок. Але трапляються теки, де файли названі за змістом («Вступ.mp3»,
 * «Місто.mp3», «Втеча.mp3»): за алфавітом книга піде в хибному порядку, хоч у кожного
 * файла в тегу є справжній номер доріжки. Тоді слухач чує главу 7 перед главою 2 — і
 * не одразу розуміє, що це не сюжет, а порядок файлів.
 *
 * Правило свідомо обережне, бо хибний тег гірший за порядок за іменами:
 *  - **імена, що всі починаються з числа, мають перевагу** — так було завжди;
 *  - теги беремо лише коли номер доріжки є в *кожного* файла й усі пари (диск, доріжка) різні;
 *  - якщо порядок за тегами збігається з порядком за іменами — нічого не міняємо.
 */
object ChapterOrdering {

    data class Entry(val name: String, val disc: Int?, val track: Int?)

    private val STARTS_WITH_NUMBER = Regex("""^\s*\d""")

    /**
     * Перестановка індексів у порядок за тегами або `null`, якщо лишати порядок за іменами.
     * Результат: `order[k]` — індекс запису, який має стати k-м.
     */
    fun byTags(entries: List<Entry>): List<Int>? {
        if (entries.size < 2) return null
        if (entries.all { STARTS_WITH_NUMBER.containsMatchIn(it.name) }) return null
        if (entries.any { it.track == null || it.track <= 0 }) return null

        val keyed = entries.mapIndexed { i, e -> Triple(i, e.disc?.takeIf { it > 0 } ?: 1, e.track!!) }
        // Дві різні глави з однаковою парою (диск, доріжка) — теги не можна довіряти.
        if (keyed.map { it.second to it.third }.toSet().size != keyed.size) return null

        val order = keyed.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
        return order.takeIf { it != entries.indices.toList() }
    }

    /** Число з рядка тегу: «3», «3/12», « 03 ». Нуль і сміття — `null`. */
    fun parseNumber(raw: String?): Int? =
        raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}
