package ua.nichnyk.listen.playback

/**
 * Сторінка списку для браузера Android Auto: `page` рахується від нуля, `pageSize` — довільне
 * число від клієнта.
 *
 * Арифметика в `Long`: клієнт має право передати майже `Int.MAX_VALUE` як розмір сторінки
 * («дай усе»), і `page * pageSize` у `Int` переповнювалось у від'ємний індекс — виняток у службі
 * замість порожньої чи повної відповіді. Недодатний розмір сторінки означає «без пагінації».
 */
fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0) return this
    val from = page.coerceAtLeast(0).toLong() * pageSize.toLong()
    if (from >= size) return emptyList()
    val to = minOf(from + pageSize.toLong(), size.toLong())
    return subList(from.toInt(), to.toInt())
}
