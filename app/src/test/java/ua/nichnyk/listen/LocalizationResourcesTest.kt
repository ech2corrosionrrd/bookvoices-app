package ua.nichnyk.listen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Ресурси локалізації як дані, а не як текст.
 *
 * Сім локалей, 320 рядків і 6 наборів множини в кожній: розбіжність тут не падає на
 * збірці й не видно на рев'ю. Пропущений рядок — це порожнє місце в інтерфейсі
 * рівно для тих користувачів, чиєю мовою ти не перевіряєш; зайвий `%s` у перекладі
 * — це `IllegalFormatException` на живому пристрої, теж лише в чужій локалі.
 *
 * TESTING.md обіцяв цю перевірку задовго до того, як вона зʼявилася. Тепер вона
 * справді є: тест читає самі XML, а не ресурси через Robolectric, — тож бачить
 * усі локалі одночасно, чого застосунок у рантаймі ніколи не робить.
 */
class LocalizationResourcesTest {

    /**
     * Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`.
     * Якщо це колись зміниться, тест впаде на порожньому списку локалей нижче,
     * а не мовчки почне нічого не перевіряти.
     */
    private val resDir = File("src/main/res")

    /**
     * Скільки форм множини вимагає CLDR для кожної мови.
     *
     * Списком, а не «як у типовій»: словʼянські мови мають few/many, романо-германські
     * — ні, і однаковий набір для всіх був би або надлишком, або помилкою. Сьома мова
     * не пройде цей тест, доки її форми не додадуть сюди свідомо.
     */
    private val requiredQuantities = mapOf(
        // Типова локаль — англійська, і окремої values-en немає: пристрій з «en»
        // резолвиться саме сюди. Це не спрощення, а те, що вирішує долю локалі
        // поза списком: усе, чого немає серед values-*, падає в типову.
        "values" to setOf("one", "other"), // англійська, типова
        "values-uk" to setOf("one", "few", "many", "other"),
        "values-pl" to setOf("one", "few", "many", "other"),
        "values-de" to setOf("one", "other"),
        "values-es" to setOf("one", "other"),
        "values-fr" to setOf("one", "other"),
        // CLDR має для португальської ще й «many», але лише для компактних чисел
        // («1 milhão»); у застосунку таких немає, тож набір той самий, що в решти
        // романських — як і в es/fr вище.
        "values-pt-rBR" to setOf("one", "other"),
    )

    /** `values-pt-rBR` → `pt-BR`: у ресурсах регіон пишеться з `r`, у BCP-47 — ні. */
    private fun localeTag(dirName: String): String =
        if (dirName == "values") DEFAULT_LOCALE
        else dirName.removePrefix("values-").replace(Regex("-r([A-Z]{2})$"), "-$1")

    /** `%s`, `%d`, `%1$s`… але не `%%` — це екранований відсоток, а не аргумент. */
    private val placeholder = Regex("""%(?:(\d+)\$)?([sdf])""")

    private data class Bundle(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun localeDirs(): List<File> =
        resDir.listFiles { f: File -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .filter { File(it, "strings.xml").exists() }
            .sortedBy { it.name }

    private fun parse(dir: File): Bundle {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(dir, "strings.xml"))
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val root = doc.documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    assertTrue("${dir.name}: рядок «$name» оголошено двічі", name !in strings)
                    strings[name] = node.textContent
                }
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val itemNodes = node.childNodes
                    for (j in 0 until itemNodes.length) {
                        val item = itemNodes.item(j) as? Element ?: continue
                        if (item.tagName == "item") items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[name] = items
                }
            }
        }
        return Bundle(strings, plurals)
    }

    private fun signature(value: String): List<String> =
        placeholder.findAll(value).map { it.value }.toList()

    @Test
    fun everyLocaleIsPresentAndKnown() {
        val dirs = localeDirs().map { it.name }
        assertTrue("не знайдено жодного strings.xml — перевірте робочий каталог тесту", dirs.isNotEmpty())
        assertTrue("типова локаль values/ обовʼязкова", "values" in dirs)
        val unknown = dirs - requiredQuantities.keys
        assertTrue(
            "локаль $unknown додали в ресурси, але не описали форми множини в цьому тесті",
            unknown.isEmpty(),
        )
    }

    /**
     * Мова типової локалі та список у `locales_config.xml` — те саме.
     *
     * Android бере `values/` для будь-якої локалі, якої немає серед `values-*`.
     * Доки типова була українською, італієць чи португалець отримували
     * український інтерфейс — дефект, який видно лише на пристрої з такою
     * локаллю, тобто ніколи. Тепер типова англійська, і окремої `values-en`
     * немає навмисно: вона дублювала б типову рядок у рядок, а саме через таке
     * дублювання свого часу прибрали `values-uk` (28 помилок `MissingTranslation`).
     */
    @Test
    fun localesConfigMatchesTheResources() {
        val declared = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(resDir, "xml/locales_config.xml"))
            .getElementsByTagName("locale")
            .let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") } }
            .toSet()

        val dirs = localeDirs().map { it.name }
        assertTrue(
            "values-en дублювала б типову локаль — тримайте англійську лише у values/",
            "values-en" !in dirs,
        )
        val fromResources = dirs.map(::localeTag).toSet()
        assertEquals("locales_config.xml розійшовся з каталогами ресурсів", fromResources, declared)
    }

    @Test
    fun everyLocaleHasExactlyTheSameKeys() {
        val reference = parse(resDir.resolve("values"))
        for (dir in localeDirs()) {
            if (dir.name == "values") continue
            val bundle = parse(dir)

            val missingStrings = reference.strings.keys - bundle.strings.keys
            val extraStrings = bundle.strings.keys - reference.strings.keys
            assertEquals("${dir.name}: бракує рядків", emptySet<String>(), missingStrings)
            assertEquals("${dir.name}: зайві рядки, яких немає у values/", emptySet<String>(), extraStrings)

            val missingPlurals = reference.plurals.keys - bundle.plurals.keys
            val extraPlurals = bundle.plurals.keys - reference.plurals.keys
            assertEquals("${dir.name}: бракує наборів множини", emptySet<String>(), missingPlurals)
            assertEquals("${dir.name}: зайві набори множини", emptySet<String>(), extraPlurals)
        }
    }

    /**
     * Найдорожча з можливих розбіжностей: зайвий або втрачений аргумент формату
     * кидає виняток у рантаймі — і тільки в тій мові, якою ніхто не перевіряв.
     */
    @Test
    fun placeholdersMatchTheDefaultLocale() {
        val reference = parse(resDir.resolve("values"))
        for (dir in localeDirs()) {
            if (dir.name == "values") continue
            val bundle = parse(dir)
            for ((key, expected) in reference.strings) {
                val actual = bundle.strings[key] ?: continue
                assertEquals(
                    "${dir.name}: рядок «$key» має інші аргументи формату",
                    signature(expected).sorted(),
                    signature(actual).sorted(),
                )
            }
            for ((key, expectedForms) in reference.plurals) {
                val actualForms = bundle.plurals[key] ?: continue
                // Порівнюємо з формою «other»: вона є в кожній мові, а решта форм
                // тієї самої множини мусить нести ті самі аргументи.
                val expected = expectedForms["other"].orEmpty()
                for ((quantity, value) in actualForms) {
                    assertEquals(
                        "${dir.name}: множина «$key», форма «$quantity» має інші аргументи формату",
                        signature(expected).sorted(),
                        signature(value).sorted(),
                    )
                }
            }
        }
    }

    @Test
    fun pluralsCoverEveryFormTheLanguageNeeds() {
        for (dir in localeDirs()) {
            val required = requiredQuantities.getValue(dir.name)
            val bundle = parse(dir)
            for ((key, forms) in bundle.plurals) {
                val missing = required - forms.keys
                assertEquals(
                    "${dir.name}: множина «$key» без обовʼязкових для цієї мови форм",
                    emptySet<String>(),
                    missing,
                )
                val unexpected = forms.keys - setOf("zero", "one", "two", "few", "many", "other")
                assertEquals(
                    "${dir.name}: множина «$key» має форму, якої немає в CLDR",
                    emptySet<String>(),
                    unexpected,
                )
            }
        }
    }

    @Test
    fun nothingIsLeftUntranslated() {
        val reference = parse(resDir.resolve("values"))
        for (dir in localeDirs()) {
            if (dir.name == "values") continue
            val bundle = parse(dir)
            for ((key, value) in bundle.strings) {
                assertTrue("${dir.name}: рядок «$key» порожній", value.isNotBlank())
                // Однакове значення саме по собі не помилка (назва застосунку,
                // «OK», символи), тому перевіряємо лише те, що переклад узагалі є.
                assertTrue("${key} зник із типової локалі", key in reference.strings)
            }
        }
    }

    private companion object {
        /** Мова, яку віддає `values/` — і яку побачить кожен, чиєї локалі немає в списку. */
        const val DEFAULT_LOCALE = "en"
    }
}
