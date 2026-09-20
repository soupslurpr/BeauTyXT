package dev.soupslurpr.beautyxt.markdown

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies stable heading destinations and immutable, reusable lookup state. */
class MarkdownHeadingIndexTest {
    @Test
    fun resolvesUnicodeAndDuplicateHeadingAnchors() {
        val items =
            listOf(
                headingItem(0, "Overview"),
                headingItem(1, "Café 日本語"),
                headingItem(2, "Overview"),
                headingItem(3, "Overview"),
                MarkdownPreviewHeadingItem(4, isHeading = false, text = "Overview")
            )

        assertEquals(0, MarkdownHeadingIndex(items).itemIndex("overview"))
        assertEquals(1, MarkdownHeadingIndex(items).itemIndex("café-日本語"))
        assertEquals(2, MarkdownHeadingIndex(items).itemIndex("overview-1"))
        assertEquals(3, MarkdownHeadingIndex(items).itemIndex("overview-2"))
        assertNull(MarkdownHeadingIndex(items).itemIndex("missing"))
    }

    @Test
    fun treatsAnEmptyFragmentAsTheTopOfANonemptyPreview() {
        val items = listOf(MarkdownPreviewHeadingItem(0, isHeading = false, text = "Body"))

        assertEquals(0, MarkdownHeadingIndex(items).itemIndex(""))
        assertNull(MarkdownHeadingIndex(emptyList()).itemIndex(""))
    }

    @Test
    fun preservesCollisionsBetweenDuplicateAndExplicitlyNumberedHeadings() {
        val items =
            listOf("Note", "Note-1", "Note", "Note-1", "Note", "NOTE!!!")
                .mapIndexed { itemIndex, text -> headingItem(itemIndex, text) }

        listOf("note", "note-1", "note-2", "note-1-1", "note-3", "note-4")
            .forEachIndexed { itemIndex, fragment ->
                assertEquals(itemIndex, MarkdownHeadingIndex(items).itemIndex(fragment))
            }
    }

    @Test
    fun ignoresHeadingsWithoutAnchorCharacters() {
        val items =
            listOf(
                headingItem(0, "!!!"),
                headingItem(1, "---"),
                headingItem(2, "Note"),
                headingItem(3, "Note")
            )

        assertEquals(2, MarkdownHeadingIndex(items).itemIndex("note"))
        assertEquals(3, MarkdownHeadingIndex(items).itemIndex("note-1"))
        assertNull(MarkdownHeadingIndex(items).itemIndex("!!!"))
    }

    @Test
    fun resolvesTheLastOfManyIdenticalHeadings() {
        val headingCount = 8_192
        val items = List(headingCount) { itemIndex -> headingItem(itemIndex, "Notes") }

        assertEquals(
            headingCount - 1,
            MarkdownHeadingIndex(items).itemIndex("notes-${headingCount - 1}")
        )
    }

    @Test
    fun retainsOnlyItsIndexInsteadOfTheInputCollection() {
        val items = mutableListOf(headingItem(3, "Original"))
        val index = MarkdownHeadingIndex(items)
        items.clear()
        items += headingItem(7, "Replacement")

        assertEquals(3, index.itemIndex("original"))
        assertNull(index.itemIndex("replacement"))
        assertEquals(0, index.itemIndex(""))
    }

    @Test
    fun rejectsInvalidItemIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            MarkdownHeadingIndex(listOf(headingItem(-1, "Invalid")))
        }
    }

    @Test
    fun preservesFirstAvailableSuffixesAcrossMixedCollisions() {
        val random = Random(0)
        val names = listOf("A", "A-1", "A-1-1", "A-2", "B!", "B", "!!!", "Café", "Café")
        val items =
            List(1_024) { itemIndex -> headingItem(itemIndex, names[random.nextInt(names.size)]) }
        val expected = mutableMapOf<String, Int>()
        items.forEach { item ->
            val base = markdownHeadingAnchor(item.text)
            if (base.isEmpty()) return@forEach
            var anchor = base
            var suffix = 0
            while (anchor in expected) {
                suffix += 1
                anchor = "$base-$suffix"
            }
            expected[anchor] = item.itemIndex
        }
        val index = MarkdownHeadingIndex(items)

        expected.forEach { (anchor, itemIndex) -> assertEquals(itemIndex, index.itemIndex(anchor)) }
    }

    /** Creates one heading item for anchor resolution. */
    private fun headingItem(itemIndex: Int, text: String): MarkdownPreviewHeadingItem =
        MarkdownPreviewHeadingItem(itemIndex = itemIndex, isHeading = true, text = text)
}
