package com.labteto.dshmobile.core.markdown

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class IncrementalMarkdownTest {
    private val examples = listOf(
        "plain text\ncontinues\n\nlast *paragraph* [link](https://example.test)",
        "# Heading\n\nfirst\n\n## Next\n\n**unfinished bold",
        "| one | two |\n| :--- | ---: |\n| cell | value |\n\nafter",
        "before\n| h | other |\n|---|---|\n| row | value |",
        "```kotlin\nval a = 1\n\n# not a heading\n```\n\nafter",
        "  ```lang\n\nbody\n\n  ```\n\n1. item\n2. next",
        "- first\n  - nested\n\n- next\n\n> quote\n> continued\n\nnormal",
        "- [x] checked\n- [ ] unchecked\n\n---\n\n***\n\n_ _ _",
        "# CRLF\r\n\r\n|a|b|\r\n|--|--|\r\n|v|w|\r\n\r\nlast\r",
        "```unclosed\n\ninside\n\n```partial closing\n\nnew",
        "\n\n\t\n# H\n\n> q\n\n| header |\n\n---\n",
    )

    @Test fun `every character prefix equals the full parser including retrospective Markdown changes`() {
        for (document in examples) {
            val incremental = IncrementalMarkdown()
            for (end in 0..document.length) {
                val prefix = document.substring(0, end)
                assertEquals("prefix $end of $document", parseMarkdown(prefix), incremental.update(prefix))
            }
        }
    }

    @Test fun `all two part splits including split CRLF and fences preserve exact parser output`() {
        for (document in examples) for (split in 0..document.length) {
            val incremental = IncrementalMarkdown()
            incremental.update(document.substring(0, split))
            assertEquals("split $split of $document", parseMarkdown(document), incremental.update(document))
        }
    }

    @Test fun `random grammar line combinations equal full parsing across arbitrary streaming splits`() {
        val random = Random(1984)
        val lines = listOf("", "paragraph", "next **bold**", "# title", "```", " ```x", "- item", "  - nested", "> quote", "| a | b |", "|---|:---:|", "---", "  ", "\t", "[link](url)")
        repeat(100) {
            val document = (0 until 20).joinToString(if (random.nextBoolean()) "\n" else "\r\n") { lines[random.nextInt(lines.size)] }
            val incremental = IncrementalMarkdown()
            var end = 0
            while (end < document.length) {
                end = (end + random.nextInt(1, 13)).coerceAtMost(document.length)
                val prefix = document.substring(0, end)
                assertEquals(prefix, parseMarkdown(prefix), incremental.update(prefix))
            }
        }
    }

    @Test fun `completed blocks are reused and completed source is not reparsed for new tokens`() {
        val incremental = IncrementalMarkdown()
        val prefix = (0 until 100).joinToString("") { "paragraph $it\n\n" }
        val old = incremental.update(prefix)
        val original = old.toList()
        val processed = incremental.parsedCharacters
        var text = prefix
        repeat(20) {
            text += "x"
            val next = incremental.update(text)
            assertSame(old.first(), next.first())
            assertEquals(parseMarkdown(text), next)
        }
        assertEquals(original, old)
        assertEquals(210L, incremental.parsedCharacters - processed)
        assertSame(incremental.update(text), incremental.update(text))
    }

    @Test fun `rewinds replacements and CRLF edits reset context without mutating published blocks`() {
        val incremental = IncrementalMarkdown()
        val old = incremental.update("# old\n\nparagraph\n\n```x\n\ncode")
        val copy = old.toList()
        listOf("# new\n\nother", "# new", "# new\r", "# new\r\n\r\n|a|b|\r\n|--|--|", "", "> quote\n\nend").forEach { text ->
            assertEquals(parseMarkdown(text), incremental.update(text))
        }
        assertEquals(copy, old)
    }
}
