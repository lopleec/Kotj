package com.lopleec.kotj.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.lopleec.kotj.data.readLimited
import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.ImportPreview
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.RichSpan
import com.lopleec.kotj.model.TextKind
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.XMLConstants

object NoteImporter {
    fun read(resolver: ContentResolver, uri: Uri, mimeType: String?): ImportPreview {
        val name = displayName(resolver, uri)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "仅支持由系统文件选择器提供的文件" }
        val bytes = resolver.openInputStream(uri)?.use { it.readLimited(MAX_IMPORT_BYTES) }
            ?: error("无法读取文件")
        return try {
            readBytes(name, mimeType, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readBytes(name: String, mimeType: String?, bytes: ByteArray): ImportPreview {
        val extension = name.substringAfterLast('.', "").lowercase()
        val docx = extension == "docx" || mimeType == DOCX_MIME
        if (!docx) require(bytes.size <= MAX_TEXT_IMPORT_BYTES) { "文本文件超过 8 MB 限制" }
        val document = when {
            docx -> parseDocx(bytes, name)
            extension == "md" || extension == "markdown" || mimeType?.contains("markdown") == true ->
                parseMarkdown(bytes.toString(Charsets.UTF_8), name)
            extension == "rtf" || mimeType?.contains("rtf") == true ->
                parsePlain(parseRtf(bytes.toString(Charsets.UTF_8)), name)
            else -> parsePlain(decodeText(bytes), name)
        }
        return ImportPreview(sourceName = name, document = document)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val fromProvider = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return fromProvider?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported note"
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun parsePlain(text: String, name: String): NoteDocument {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        val title = name.substringBeforeLast('.').ifBlank { "Imported note" }
        val blocks = normalized.split(Regex("\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { NoteBlock(text = it, textKind = TextKind.BODY) }
        return NoteDocument(title = title, blocks = blocks)
    }

    private fun parseMarkdown(source: String, name: String): NoteDocument {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
        var title = name.substringBeforeLast('.').ifBlank { "Imported note" }
        val blocks = mutableListOf<NoteBlock>()
        var paragraph = mutableListOf<String>()

        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += markdownTextBlock(paragraph.joinToString("\n"), TextKind.BODY)
            paragraph = mutableListOf()
        }

        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.startsWith("# ") && blocks.isEmpty() && paragraph.isEmpty() -> title = stripMarkdown(line.removePrefix("# ")).first
                line.startsWith("### ") -> { flush(); blocks += markdownTextBlock(line.removePrefix("### "), TextKind.SUBHEADING) }
                line.startsWith("## ") -> { flush(); blocks += markdownTextBlock(line.removePrefix("## "), TextKind.HEADING) }
                line == "---" || line == "***" -> { flush(); blocks += NoteBlock(type = BlockType.DIVIDER) }
                line.startsWith("> ") -> { flush(); blocks += markdownTextBlock(line.removePrefix("> "), TextKind.QUOTE) }
                line.startsWith("- [ ] ", ignoreCase = true) -> {
                    flush()
                    blocks += markdownTextBlock(line.substring(6), TextKind.CHECKLIST).copy(checked = false)
                }
                line.startsWith("- [x] ", ignoreCase = true) -> {
                    flush()
                    blocks += markdownTextBlock(line.substring(6), TextKind.CHECKLIST).copy(checked = true)
                }
                else -> paragraph += line
            }
        }
        flush()
        return NoteDocument(title = title, blocks = blocks)
    }

    private fun markdownTextBlock(source: String, kind: TextKind): NoteBlock {
        val (plain, spans) = stripMarkdown(source)
        return NoteBlock(text = plain, textKind = kind, spans = spans)
    }

    private fun stripMarkdown(source: String): Pair<String, List<RichSpan>> {
        val parsed = parseMarkdownInline(source)
        return parsed.text to parsed.spans
    }

    private data class ParsedInline(val text: String, val spans: List<RichSpan>)

    private fun parseMarkdownInline(source: String, depth: Int = 0): ParsedInline {
        if (depth >= MAX_MARKDOWN_NESTING) return ParsedInline(source, emptyList())
        val output = StringBuilder()
        val spans = mutableListOf<RichSpan>()
        var index = 0

        fun appendNested(inner: ParsedInline, style: (Int, Int) -> RichSpan) {
            val offset = output.length
            output.append(inner.text)
            spans += inner.spans.map { it.copy(start = it.start + offset, end = it.end + offset) }
            if (inner.text.isNotEmpty()) spans += style(offset, output.length)
        }

        while (index < source.length) {
            val colorHeader = COLOR_SPAN_HEADER.find(source, index)
                ?.takeIf { it.range.first == index }
            if (colorHeader != null) {
                val close = source.indexOf("</span>", colorHeader.range.last + 1)
                if (close >= 0) {
                    val color = colorHeader.groupValues[1].toLong(16)
                    val innerStart = colorHeader.range.last + 1
                    appendNested(parseMarkdownInline(source.substring(innerStart, close), depth + 1)) { start, end ->
                        RichSpan(start, end, colorArgb = 0xFF000000L or color)
                    }
                    index = close + "</span>".length
                    continue
                }
            }
            if (source.startsWith("<u>", index)) {
                val close = source.indexOf("</u>", index + 3)
                if (close >= 0) {
                    appendNested(parseMarkdownInline(source.substring(index + 3, close), depth + 1)) { start, end ->
                        RichSpan(start, end, underline = true)
                    }
                    index = close + 4
                    continue
                }
            }
            val marker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("~~", index) -> "~~"
                source[index] == '*' -> "*"
                else -> null
            }
            if (marker != null) {
                val close = source.indexOf(marker, index + marker.length)
                if (close >= 0) {
                    appendNested(parseMarkdownInline(source.substring(index + marker.length, close), depth + 1)) { start, end ->
                        when (marker) {
                            "**" -> RichSpan(start, end, bold = true)
                            "~~" -> RichSpan(start, end, strikeThrough = true)
                            else -> RichSpan(start, end, italic = true)
                        }
                    }
                    index = close + marker.length
                    continue
                }
            }
            output.append(source[index++])
        }
        return ParsedInline(output.toString(), spans)
    }

    private fun parseRtf(source: String): String {
        val output = StringBuilder()
        var index = 0
        var skipDestination = false
        while (index < source.length) {
            when (val char = source[index]) {
                '{' -> { index++ }
                '}' -> { skipDestination = false; index++ }
                '\\' -> {
                    index++
                    if (index >= source.length) break
                    when (source[index]) {
                        '\\', '{', '}' -> output.append(source[index++])
                        '\'' -> {
                            val hex = source.substring(index + 1, (index + 3).coerceAtMost(source.length))
                            hex.toIntOrNull(16)?.let { output.append(it.toChar()) }
                            index = (index + 3).coerceAtMost(source.length)
                        }
                        '*' -> { skipDestination = true; index++ }
                        else -> {
                            val start = index
                            while (index < source.length && source[index].isLetter()) index++
                            val word = source.substring(start, index)
                            if (index < source.length && (source[index] == '-' || source[index].isDigit())) {
                                index++
                                while (index < source.length && source[index].isDigit()) index++
                            }
                            if (index < source.length && source[index] == ' ') index++
                            if (!skipDestination && word in setOf("par", "line")) output.append('\n')
                            if (!skipDestination && word == "tab") output.append('\t')
                        }
                    }
                }
                '\r', '\n' -> index++
                else -> { if (!skipDestination) output.append(char); index++ }
            }
        }
        return output.toString().trim()
    }

    private fun parseDocx(bytes: ByteArray, name: String): NoteDocument {
        val documentXml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entryCount = 0
            var document: ByteArray? = null
            while (document == null) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_DOCX_ENTRIES) { "DOCX 包含过多文件" }
                if (entry.name == "word/document.xml") {
                    document = zip.readLimited(MAX_DOCX_XML_BYTES)
                }
                zip.closeEntry()
            }
            document
        } ?: error("无法读取 DOCX 正文")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val xml = factory.newDocumentBuilder().parse(ByteArrayInputStream(documentXml))
        val body = xml.getElementsByTagName("w:body").item(0) as? Element
            ?: return NoteDocument(title = name.substringBeforeLast('.'))
        var title = name.substringBeforeLast('.').ifBlank { "Imported note" }
        val blocks = mutableListOf<NoteBlock>()
        for (index in 0 until body.childNodes.length) {
            val child = body.childNodes.item(index) as? Element ?: continue
            when (child.tagName) {
                "w:p" -> {
                    val parsed = parseDocxParagraph(child)
                    if (parsed.text.isBlank()) continue
                    if (parsed.textKind == TextKind.TITLE && blocks.isEmpty()) title = parsed.text else blocks += parsed
                }
                "w:tbl" -> blocks += parseDocxTable(child)
            }
        }
        return NoteDocument(title = title, blocks = blocks)
    }

    private fun parseDocxParagraph(paragraph: Element): NoteBlock {
        val styleName = (paragraph.getElementsByTagName("w:pStyle").item(0) as? Element)?.getAttribute("w:val")
        val kind = when (styleName) {
            "Title" -> TextKind.TITLE
            "Heading1" -> TextKind.HEADING
            "Heading2", "Subtitle" -> TextKind.SUBHEADING
            "Quote" -> TextKind.QUOTE
            else -> TextKind.BODY
        }
        val text = StringBuilder()
        val spans = mutableListOf<RichSpan>()
        val runs = paragraph.getElementsByTagName("w:r")
        for (index in 0 until runs.length) {
            val run = runs.item(index) as? Element ?: continue
            val start = text.length
            val nodes = run.childNodes
            for (nodeIndex in 0 until nodes.length) {
                val node = nodes.item(nodeIndex) as? Element ?: continue
                when (node.tagName) {
                    "w:t" -> text.append(node.textContent)
                    "w:br" -> text.append('\n')
                }
            }
            val end = text.length
            val properties = run.getElementsByTagName("w:rPr").item(0) as? Element
            if (start < end && properties != null) {
                val color = (properties.getElementsByTagName("w:color").item(0) as? Element)
                    ?.getAttribute("w:val")?.takeIf { it.length == 6 }?.toLongOrNull(16)
                val span = RichSpan(
                    start = start,
                    end = end,
                    bold = properties.getElementsByTagName("w:b").length > 0,
                    italic = properties.getElementsByTagName("w:i").length > 0,
                    underline = properties.getElementsByTagName("w:u").length > 0,
                    strikeThrough = properties.getElementsByTagName("w:strike").length > 0,
                    colorArgb = color?.let { 0xFF000000L or it },
                )
                if (span.bold || span.italic || span.underline || span.strikeThrough || span.colorArgb != null) spans += span
            }
        }
        return NoteBlock(text = text.toString(), textKind = kind, spans = spans)
    }

    private fun parseDocxTable(table: Element): NoteBlock {
        val rows = mutableListOf<List<String>>()
        val rowNodes = table.childNodes
        for (rowIndex in 0 until rowNodes.length) {
            val row = rowNodes.item(rowIndex) as? Element ?: continue
            if (row.tagName != "w:tr") continue
            val cells = mutableListOf<String>()
            val cellNodes = row.childNodes
            for (cellIndex in 0 until cellNodes.length) {
                val cell = cellNodes.item(cellIndex) as? Element ?: continue
                if (cell.tagName == "w:tc") {
                    val texts = cell.getElementsByTagName("w:t")
                    cells += buildString { for (textIndex in 0 until texts.length) append(texts.item(textIndex).textContent) }
                }
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return NoteBlock(type = BlockType.TABLE, tableCells = rows)
    }

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
    private const val MAX_TEXT_IMPORT_BYTES = 8 * 1024 * 1024
    private const val MAX_DOCX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_DOCX_ENTRIES = 512
    private const val MAX_MARKDOWN_NESTING = 16
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private val COLOR_SPAN_HEADER = Regex("<span\\s+style=[\"']color:\\s*#([0-9a-fA-F]{6})[\"']>")
}
