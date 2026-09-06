package ua.nichnyk.listen.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.nichnyk.listen.R

val Filament = Color(0xFFF4B942)
val Ember = Color(0xFFE07A3D)
val Moon = Color(0xFFE8EEF7)
val Mist = Color(0xFF8B9BB0)
val Night = Color(0xFF0A1018)
val NightSurface = Color(0xFF141C28)
val NightRaised = Color(0xFF1C2736)
val Spine = Color(0xFF3D5A80)
val Paper = Color(0xFFF4EBD8)
val PaperSurface = Color(0xFFFFF8EC)
val Ink = Color(0xFF1A140C)
val Leather = Color(0xFF8A4B12)

val Cormorant = FontFamily(
    Font(R.font.cormorant_medium, FontWeight.Medium),
    Font(R.font.cormorant_semibold, FontWeight.SemiBold),
)

val Plex = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_medium, FontWeight.Medium),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
)

// Кожна схема визначає всі роли, до яких хтось звертається, — включно з тими,
// що їх називає не наш код, а самі компоненти M3 за замовчуванням. Пропущена
// роль не «успадковується» від сусідньої: вона бере стокове значення з базової
// (лілово-фіолетової) палітри Material і мовчки вибивається з теми.
// Так, до цього списку, вибраний FilterChip був лілово-сірий у всіх трьох темах.
private val NightColors = darkColorScheme(
    primary = Filament,
    onPrimary = Night,
    primaryContainer = Color(0xFF3A2E16),
    onPrimaryContainer = Filament,
    secondary = Ember,
    onSecondary = Night,
    // secondaryContainer — це, зокрема, тло вибраного FilterChip (25 місць).
    secondaryContainer = Color(0xFF3D2517),
    onSecondaryContainer = Color(0xFFF0A472),
    background = Night,
    onBackground = Moon,
    surface = NightSurface,
    onSurface = Moon,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Mist,
    // Родина surfaceContainer* — тло ModalBottomSheet (BottomSheetDefaults) і
    // карток із tonalElevation. Три з п'яти шторок покладаються саме на неї.
    surfaceContainerLowest = Color(0xFF070C12),
    surfaceContainerLow = Color(0xFF101823),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = Color(0xFF243142),
    // surfaceTint домішується до Surface із tonalElevation (LibraryScreen).
    surfaceTint = Filament,
    // inverseSurface/inverseOnSurface — тло й текст Snackbar.
    inverseSurface = Moon,
    inverseOnSurface = Night,
    outline = Spine,
    outlineVariant = Color(0xFF2A3545),
    error = Color(0xFFE57373),
    onError = Night,
    errorContainer = Color(0xFF4A2020),
    onErrorContainer = Color(0xFFF2B8B5),
    scrim = Color.Black,
)

private val PaperColors = lightColorScheme(
    primary = Leather,
    onPrimary = Paper,
    primaryContainer = Color(0xFFE7D3B0),
    onPrimaryContainer = Ink,
    secondary = Ember,
    onSecondary = Paper,
    secondaryContainer = Color(0xFFF7DCC4),
    onSecondaryContainer = Color(0xFF4A2410),
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEADFCB),
    onSurfaceVariant = Color(0xFF5C5346),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFBF3),
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = Color(0xFFF7EEDC),
    surfaceContainerHighest = Color(0xFFEFE4CE),
    surfaceTint = Leather,
    inverseSurface = Color(0xFF2A2318),
    inverseOnSurface = Paper,
    outline = Color(0xFFB7A78C),
    outlineVariant = Color(0xFFDDD0B8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF7DAD5),
    onErrorContainer = Color(0xFF5C1710),
    // Не чорний: у світлій темі затемнення під мультивибором має бути чорнилом,
    // а не дірою. Стоковий scrim у M3 чорний в обох схемах.
    scrim = Ink,
)

private val OledColors = darkColorScheme(
    primary = Filament,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A200F),
    onPrimaryContainer = Filament,
    secondary = Ember,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A180E),
    onSecondaryContainer = Color(0xFFF0A472),
    background = Color.Black,
    onBackground = Moon,
    surface = Color(0xFF0F0F0F),
    onSurface = Moon,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Mist,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
    surfaceTint = Filament,
    inverseSurface = Moon,
    inverseOnSurface = Color.Black,
    outline = Color(0xFF2D3748),
    outlineVariant = Color(0xFF1E2633),
    error = Color(0xFFE57373),
    onError = Color.Black,
    errorContainer = Color(0xFF331414),
    onErrorContainer = Color(0xFFF2B8B5),
    scrim = Color.Black,
)

private val ListenTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = Cormorant, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = Cormorant, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Cormorant, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Cormorant, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 28.sp),
    // headlineSmall — редакційний заголовок: секції налаштувань, назва автора й
    // серії у верхній панелі. Раніше слот не був визначений, тож сім заголовків
    // секцій малювалися системним Roboto — єдиний такий текст у застосунку.
    headlineSmall = TextStyle(fontFamily = Cormorant, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp),
)

/**
 * Радіуси заокруглення.
 *
 * До цього шару дизайн-системи не існувало: `MaterialTheme.shapes` не був
 * переозначений і не використовувався ні разу, а по екранах було розсипано
 * 29 захардкоджених `RoundedCornerShape` у девʼяти різних значеннях — разом із
 * одноразовими 2, 14 і 32, які просто розійшлися з сусідами.
 *
 * `extraLarge` = 28: саме його M3 бере під верх модальних шторок
 * (`BottomSheetDefaults`), і той самий радіус має обкладинка в плеєрі.
 */
private val ListenShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Домінантний радіус карток — 14 місць, від полиці до шторки швидкості.
 *
 * Окремою константою, а не слотом: у пʼяти слотах M3 йому місця не лишилося,
 * і зайняти ним `extraLarge` означало б забрати 28 у шторок та обкладинки.
 */
val CardShape = RoundedCornerShape(20.dp)

/**
 * Тло піднятої картки.
 *
 * Одна константа замість чотирьох. Та сама роль — картка на полиці, у шторці,
 * у списку персонажів, у черзі плеєра — була розписана альфами 0.4, 0.45, 0.5 і
 * 0.85. Різниця між 0.45 і 0.5 оком не читається взагалі, тобто це були не
 * рішення, а дрейф.
 *
 * 0.85 у [Components] лишився окремо й навмисно: там це не картка, а незаповнений
 * стан кружка вибору, який має триматися щільним на тлі змісту.
 */
val cardSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

/**
 * Лінія-розділювач між рядками налаштувань і секціями.
 *
 * Одна константа замість двох значень тієї самої ролі: шість розділювачів в
 * налаштуваннях стояли на 0.4, а сьомий, у шторці швидкості плеєра, — на 0.5.
 * Різниця оком не читається, тобто це було не рішення, а той самий дрейф, який
 * [cardSurface] свого часу прибрав для карток.
 */
val dividerLine: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

/**
 * Незаповнена частина смужки прогресу — на картці полиці, у рядку списку та
 * порожній стовпчик у гістограмі історії.
 *
 * Було три числа на одну роль: 0.3 на картці, 0.25 у рядку, 0.2 у гістограмі.
 *
 * Смужка під міні-плеєром лишається на суцільному `outlineVariant` навмисно: там
 * це не трек усередині картки, а власна лінія завтовшки 2 dp на межі панелі, і
 * напівпрозорий трек на ній читався б як брудна пляма.
 */
val trackSurface: Color
    @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

/**
 * Крок відступів між блоками.
 *
 * До цього рахунок вели тринадцятьма різними числами (2, 4, 6, 8, 10, 12, 14,
 * 16, 18, 20, 24, 36, 96), і ніщо не заважало зʼявитися чотирнадцятому. Тепер
 * крок має ім'я, а нове число доводиться додавати сюди свідомо.
 *
 * Проміжні 10 і 14 зведені до 8 і 12: вони не несли власного змісту й стояли
 * поряд із сусідами в тій самій ролі. Одинокі 18 і 36 лишилися тільки там, де
 * вони й були розмірами елементів, а не кроком ритму — іконка, стовпчик
 * гістограми, кнопка.
 *
 * Шкала покриває **всі** відступи в `ui/`. Довгий час це було не так: токени
 * жили поряд зі старими літералами приблизно навпіл, разом із тими самими 10 і
 * 14, які цей коментар оголошував зведеними. Числом лишається рівно те, що не є
 * кроком ритму: нижній просвіт під міні-плеєром і навігацією (96, 100, 120) та
 * оптичний відступ обкладинки в лампі плеєра (34).
 *
 * [hair] — оптичний зазор між заголовком і підписом під ним, а не крок сітки.
 * [xxxl] — нижній просвіт шторки й порожнього стану, сім місць. Свого часу він
 * лишався літералом 32, тобто єдиним кроком ритму поза шкалою — а сенс шкали в
 * тому, що нове число доводиться додавати сюди свідомо.
 */
object Spacing {
    val hair = 2.dp
    val xxs = 4.dp
    val xs = 6.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

@Composable
fun ListenTheme(theme: ua.nichnyk.listen.data.AppTheme, content: @Composable () -> Unit) {
    val target: ColorScheme = when (theme) {
        ua.nichnyk.listen.data.AppTheme.PAPER -> PaperColors
        ua.nichnyk.listen.data.AppTheme.OLED -> OledColors
        ua.nichnyk.listen.data.AppTheme.NIGHT -> NightColors
    }
    val animated = target.copy(
        background = animateColor(target.background),
        surface = animateColor(target.surface),
        onBackground = animateColor(target.onBackground),
        onSurface = animateColor(target.onSurface),
        primary = animateColor(target.primary),
        surfaceVariant = animateColor(target.surfaceVariant),
    )
    MaterialTheme(
        colorScheme = animated,
        typography = ListenTypography,
        shapes = ListenShapes,
        content = content,
    )
}

@Composable
fun ListenTheme(paper: Boolean, content: @Composable () -> Unit) {
    ListenTheme(if (paper) ua.nichnyk.listen.data.AppTheme.PAPER else ua.nichnyk.listen.data.AppTheme.NIGHT, content)
}

@Composable
private fun animateColor(target: Color): Color {
    val value by animateColorAsState(target, tween(450), label = "theme")
    return value
}
