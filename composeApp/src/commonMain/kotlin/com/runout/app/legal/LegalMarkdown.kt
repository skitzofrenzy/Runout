package com.runout.app.legal

/** A run of inline text. [target] is set for links. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val target: String? = null,
)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class BulletList(val items: List<List<InlineSpan>>) : MarkdownBlock

    /** A table. Each row has one entry per column, aligned with [header]. */
    data class Table(val header: List<List<InlineSpan>>, val rows: List<List<List<InlineSpan>>>) : MarkdownBlock
}

/**
 * A small Markdown reader for the subset the bundled documents use: `#` headings, paragraphs, `-` bullet lists,
 * pipe tables, `**bold**`, `` `code` ``, `[label](target)` links and `<https://...>` autolinks.
 */
object LegalMarkdown {
    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() -> i++

                isHeading(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += MarkdownBlock.Heading(level, parseInline(trimmed.substring(level + 1).trim()))
                    i++
                }

                isBullet(trimmed) -> {
                    val items = mutableListOf<List<InlineSpan>>()
                    while (i < lines.size && isBullet(lines[i].trim())) {
                        items += parseInline(lines[i].trim().substring(2).trim())
                        i++
                    }
                    blocks += MarkdownBlock.BulletList(items)
                }

                isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()) -> {
                    val header = splitRow(trimmed).map(::parseInline)
                    i += 2
                    val rows = mutableListOf<List<List<InlineSpan>>>()
                    while (i < lines.size && isTableRow(lines[i].trim())) {
                        rows += splitRow(lines[i].trim()).map(::parseInline)
                        i++
                    }
                    blocks += MarkdownBlock.Table(header, rows)
                }

                else -> {
                    val parts = mutableListOf<String>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        val startsOtherBlock = parts.isNotEmpty() && (isHeading(t) || isBullet(t) || isTableRow(t))
                        if (t.isEmpty() || startsOtherBlock) break
                        parts += t
                        i++
                    }
                    blocks += MarkdownBlock.Paragraph(parseInline(parts.joinToString(" ")))
                }
            }
        }
        return blocks
    }

    private fun isHeading(trimmed: String): Boolean {
        val level = trimmed.takeWhile { it == '#' }.length
        return level in 1..6 && trimmed.getOrNull(level) == ' '
    }

    private fun isBullet(trimmed: String): Boolean = trimmed.startsWith("- ") || trimmed.startsWith("* ")

    private fun isTableRow(trimmed: String): Boolean =
        trimmed.length > 1 && trimmed.startsWith("|") && trimmed.endsWith("|")

    private fun isTableSeparator(trimmed: String): Boolean =
        isTableRow(trimmed) && trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }

    private fun splitRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Parses inline bold, code, links and autolinks. Unmatched markers are kept as literal text. */
    fun parseInline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                spans += InlineSpan(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '*' && text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    parseInline(text.substring(i + 2, end)).forEach { spans += it.copy(bold = true) }
                    i = end + 2
                    continue
                }
            }
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    spans += InlineSpan(text.substring(i + 1, end), code = true)
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val paren = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && paren > close) {
                    flush()
                    spans += InlineSpan(text.substring(i + 1, close), target = text.substring(close + 2, paren))
                    i = paren + 1
                    continue
                }
            }
            if (c == '<') {
                val end = text.indexOf('>', i + 1)
                if (end > i + 1) {
                    val inner = text.substring(i + 1, end)
                    if (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:")) {
                        flush()
                        spans += InlineSpan(inner, target = inner)
                        i = end + 1
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return spans
    }
}
