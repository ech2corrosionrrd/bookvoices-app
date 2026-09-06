package ua.nichnyk.listen.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Належність документа збереженому дереву.
 *
 * Через хибу саме тут імпорт папки копіював кожен файл у сховище застосунку:
 * дозвіл зберігається на дерево, а URI кожного документа всередині нього інший,
 * і пряме порівняння рядків завжди давало false.
 *
 * Тест інструментальний, бо DocumentsContract і Uri — реальні класи Android.
 */
@RunWith(AndroidJUnit4::class)
class TreeUriTest {

    private val authority = "com.android.externalstorage.documents"

    private fun tree(id: String): Uri =
        DocumentsContract.buildTreeDocumentUri(authority, id)

    private fun document(treeId: String, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree(treeId), documentId)

    @Test
    fun documentInsideGrantedTreeBelongsToIt() {
        val granted = tree("primary:Books")
        val chapter = document("primary:Books", "primary:Books/Кобзар/01.mp3")

        // Раніше тут було false — і файл дарма копіювався.
        assertTrue(AudioImporter.belongsToTree(chapter, granted))
    }

    @Test
    fun deeplyNestedDocumentStillBelongs() {
        val granted = tree("primary:Books")
        val nested = document("primary:Books", "primary:Books/Автор/Книга/CD1/01.mp3")
        assertTrue(AudioImporter.belongsToTree(nested, granted))
    }

    @Test
    fun treeUriItselfBelongsToItself() {
        val granted = tree("primary:Books")
        assertTrue(AudioImporter.belongsToTree(granted, granted))
    }

    @Test
    fun documentFromAnotherTreeDoesNotBelong() {
        val granted = tree("primary:Books")
        val foreign = document("primary:Podcasts", "primary:Podcasts/01.mp3")
        assertFalse(AudioImporter.belongsToTree(foreign, granted))
    }

    @Test
    fun documentFromAnotherProviderDoesNotBelong() {
        // Той самий шлях, але інший провайдер — доступу немає.
        val granted = tree("primary:Books")
        val other = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("com.other.provider", "primary:Books"),
            "primary:Books/01.mp3",
        )
        assertFalse(AudioImporter.belongsToTree(other, granted))
    }

    @Test
    fun plainFileUriDoesNotBelong() {
        // URI без дерева не має проходити перевірку й падати з винятком.
        val granted = tree("primary:Books")
        assertFalse(AudioImporter.belongsToTree(Uri.parse("file:///storage/x.mp3"), granted))
        assertFalse(
            AudioImporter.belongsToTree(
                Uri.parse("content://$authority/document/primary%3Ax.mp3"),
                granted,
            ),
        )
    }
}
