package ua.nichnyk.listen.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.R

/**
 * Нижня навігація для TalkBack: у кожної вкладки є назва в об'єднаному вузлі.
 *
 * 8 листопада 2026 на телефоні вкладки не мали ні тексту, ні опису, а підписи
 * віддавались із нульовими межами. TalkBack такі вузли пропускає і читав би лише
 * «вкладка, 1 з 3». Тест дивиться в те саме об'єднане дерево, яке бачить TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class TabAccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun named(text: String) = SemanticsMatcher("contentDescription містить «$text»") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it == text }
    }

    private fun noText() = SemanticsMatcher("без дубля назви в Text") { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty()
    }

    @Test
    fun everyTabHasItsNameOnce() {
        val tabs = mapOf(
            "tab_library" to R.string.tab_shelf,
            "tab_bookmarks" to R.string.tab_bookmarks,
            "tab_settings" to R.string.tab_settings,
        )
        compose.waitForIdle()
        tabs.forEach { (tag, res) ->
            val title = compose.activity.getString(res)
            compose.onNodeWithTag(tag).assert(named(title)).assert(noText())
        }
    }
}
