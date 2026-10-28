package com.labteto.dshmobile.core.markdown

/**
 * Incremental wrapper around the existing grammar, not a second Markdown grammar.
 *
 * A completed blank line outside a fenced code block is a context-independent boundary in this
 * parser: paragraphs, quotes, tables and lists cannot consume across it. Only prefixes ending at
 * such a boundary are committed. The unfinished suffix is always parsed normally, so a new table
 * separator, closing delimiter or fence may still reinterpret it exactly as a full parse would.
 * There is deliberately no suffix-size limit. Text replacements/rewinds reset the parser.
 *
 * Returned blocks and their lists are never mutated after publication. Completed block identities
 * survive later appends, allowing the UI to keep their composition/text layout results.
 */
class IncrementalMarkdown {
    private var source = ""
    private var scanOffset = 0
    private var stableEnd = 0
    private var inFence = false
    private val stableBlocks = mutableListOf<MdBlock>()
    private var result: List<MdBlock> = emptyList()
    private var initialized = false

    internal var parsedCharacters: Long = 0
        private set

    fun update(text: String): List<MdBlock> {
        if (initialized && text == source) return result
        if (!text.startsWith(source)) {
            scanOffset = 0
            stableEnd = 0
            inFence = false
            stableBlocks.clear()
        }
        var boundary = stableEnd
        while (true) {
            val end = text.indexOf('\n', scanOffset)
            if (end < 0) break
            // CRLF may be split across updates; only complete source lines are examined.
            val line = text.substring(scanOffset, end).removeSuffix("\r")
            if (line.trimStart().startsWith("```")) inFence = !inFence
            else if (!inFence && line.isBlank()) boundary = end + 1
            scanOffset = end + 1
        }
        if (boundary > stableEnd) {
            val committed = text.substring(stableEnd, boundary)
            stableBlocks.addAll(parseMarkdown(committed))
            parsedCharacters += committed.length
            stableEnd = boundary
        }
        val suffix = text.substring(stableEnd)
        parsedCharacters += suffix.length
        result = stableBlocks.toList() + parseMarkdown(suffix)
        source = text
        initialized = true
        return result
    }
}
