package com.lopleec.kotj.export

import android.content.ContentResolver
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.lopleec.kotj.data.AttachmentContent
import com.lopleec.kotj.data.readLimited
import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.RichSpan
import com.lopleec.kotj.model.TextKind
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

enum class ExportFormat(val extension: String, val mimeType: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
}

object NoteExporter {
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_START_OF_FRAME = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )
    private const val MAX_EXPORT_IMAGE_BYTES = 25 * 1024 * 1024
    private const val EMU_PER_PIXEL = 9_525.0
    private const val MAX_IMAGE_WIDTH_EMU = 5_600_000.0
    private const val MAX_IMAGE_HEIGHT_EMU = 7_400_000.0

    fun markdown(
        document: NoteDocument,
        imageReader: ((NoteBlock) -> AttachmentContent)? = null,
    ): String = buildString {
        append("# ").append(document.title.ifBlank { "无标题" }.markdownEscaped()).append("\n\n")
        if (document.subtitle.isNotBlank()) append("*").append(document.subtitle).append("*\n\n")
        document.blocks.forEach { block ->
            when (block.type) {
                BlockType.TEXT -> {
                    val prefix = when (block.textKind) {
                        TextKind.TITLE -> "# "
                        TextKind.HEADING -> "## "
                        TextKind.SUBHEADING -> "### "
                        TextKind.QUOTE -> "> "
                        TextKind.CHECKLIST -> if (block.checked) "- [x] " else "- [ ] "
                        TextKind.BODY -> ""
                    }
                    append(prefix).append(markdownText(block)).append("\n\n")
                }
                BlockType.IMAGE -> {
                    val source = imageReader?.invoke(block)
                    val target = if (source == null) {
                        block.imageUri.orEmpty()
                    } else {
                        try {
                            "data:${source.mimeType};base64,${Base64.getEncoder().encodeToString(source.bytes)}"
                        } finally {
                            source.bytes.fill(0)
                        }
                    }
                    append("![")
                        .append(block.imageCaption.ifBlank { "图片" }.markdownEscaped())
                        .append("](").append(target).append(")\n\n")
                }
                BlockType.DIVIDER -> append("---\n\n")
                BlockType.TABLE -> appendMarkdownTable(block.tableCells)
            }
        }
    }.trimEnd() + "\n"

    fun text(document: NoteDocument): String = buildString {
        append(document.title.ifBlank { "无标题" }).append('\n')
        if (document.subtitle.isNotBlank()) append(document.subtitle).append('\n')
        append('\n')
        document.blocks.forEach { block ->
            when (block.type) {
                BlockType.TEXT -> {
                    if (block.textKind == TextKind.CHECKLIST) {
                        append(if (block.checked) "[x] " else "[ ] ")
                    }
                    append(block.text).append("\n\n")
                }
                BlockType.IMAGE -> append("[图片")
                    .append(if (block.imageCaption.isBlank()) "" else "：${block.imageCaption}")
                    .append("]\n\n")
                BlockType.DIVIDER -> append("────────────\n\n")
                BlockType.TABLE -> {
                    block.tableCells.forEach { append(it.joinToString("\t")).append('\n') }
                    append('\n')
                }
            }
        }
    }.trimEnd() + "\n"

    fun writeDocx(
        document: NoteDocument,
        output: OutputStream,
        resolver: ContentResolver? = null,
        imageReader: ((NoteBlock) -> AttachmentContent)? = null,
    ) {
        ZipOutputStream(output).use { zip ->
            val images = writeImages(document, zip, resolver, imageReader)
            zip.textEntry("[Content_Types].xml", contentTypes(images.map { it.extension }.toSet()))
            zip.textEntry("_rels/.rels", rootRelationships)
            zip.textEntry("docProps/core.xml", coreProperties)
            zip.textEntry("docProps/app.xml", appProperties)
            zip.textEntry("word/styles.xml", styles)
            zip.textEntry("word/_rels/document.xml.rels", documentRelationships(images))
            zip.textEntry("word/document.xml", documentXml(document, images))
        }
    }

    private fun StringBuilder.appendMarkdownTable(rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val columnCount = rows.maxOfOrNull { it.size } ?: return
        val normalized = rows.map { row -> List(columnCount) { row.getOrElse(it) { "" } } }
        fun row(values: List<String>) = append("| ")
            .append(values.joinToString(" | ") { it.replace("|", "\\|").replace('\n', ' ') })
            .append(" |\n")
        row(normalized.first())
        row(List(columnCount) { "---" })
        normalized.drop(1).forEach(::row)
        append('\n')
    }

    private fun markdownText(block: NoteBlock): String = styledPieces(block).joinToString("") { piece ->
        var value = piece.text.markdownEscaped()
        val style = piece.style
        style.colorArgb?.let { color -> value = "<span style=\"color:#${color.toString(16).takeLast(6)}\">$value</span>" }
        if (style.underline) value = "<u>$value</u>"
        if (style.strikeThrough) value = "~~$value~~"
        if (style.italic) value = "*$value*"
        if (style.bold) value = "**$value**"
        value
    }

    private data class ImagePart(
        val blockId: String,
        val relationshipId: String,
        val fileName: String,
        val extension: String,
        val documentPropertyId: Int,
        val widthEmu: Long,
        val heightEmu: Long,
    )

    private fun writeImages(
        document: NoteDocument,
        zip: ZipOutputStream,
        resolver: ContentResolver?,
        imageReader: ((NoteBlock) -> AttachmentContent)?,
    ): List<ImagePart> {
        val images = mutableListOf<ImagePart>()
        document.blocks.filter { it.type == BlockType.IMAGE && !it.imageUri.isNullOrBlank() }
            .forEachIndexed { index, block ->
            val supplied = imageReader?.invoke(block)
            val bytes = supplied?.bytes ?: block.imageUri!!.toUri().let { uri ->
                resolver?.openInputStream(uri)?.use { it.readLimited(MAX_EXPORT_IMAGE_BYTES) }
            } ?: error("无法读取导出图片")
            try {
                val (pixelWidth, pixelHeight) = imageDimensions(bytes)
                val extension = when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                    bytes.size >= PNG_MAGIC.size && bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC) -> "png"
                    else -> "png"
                }
                val exportBytes = if (extension == "png" && !bytes.copyOfRange(0, minOf(bytes.size, PNG_MAGIC.size)).contentEquals(PNG_MAGIC)) {
                    imageAsPng(bytes)
                } else {
                    bytes
                }
                try {
                    val naturalWidth = pixelWidth.toDouble() * EMU_PER_PIXEL
                    val naturalHeight = pixelHeight.toDouble() * EMU_PER_PIXEL
                    val scale = minOf(1.0, MAX_IMAGE_WIDTH_EMU / naturalWidth, MAX_IMAGE_HEIGHT_EMU / naturalHeight)
                    val widthEmu = (naturalWidth * scale).roundToLong().coerceAtLeast(1)
                    val heightEmu = (naturalHeight * scale).roundToLong().coerceAtLeast(1)
                    val fileName = "image${index + 1}.$extension"
                    zip.binaryEntry("word/media/$fileName", exportBytes)
                    images += ImagePart(
                        blockId = block.id,
                        relationshipId = "rIdImage${index + 1}",
                        fileName = fileName,
                        extension = extension,
                        documentPropertyId = index + 1,
                        widthEmu = widthEmu,
                        heightEmu = heightEmu,
                    )
                } finally {
                    if (exportBytes !== bytes) exportBytes.fill(0)
                }
            } finally {
                bytes.fill(0)
            }
        }
        return images
    }

    private fun imageAsPng(source: ByteArray): ByteArray {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size)) {
            "无法解码图片"
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法转换图片"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageDimensions(source: ByteArray): Pair<Int, Int> {
        if (source.size >= 24 && source.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) {
            val width = source.bigEndianInt(16)
            val height = source.bigEndianInt(20)
            if (width > 0 && height > 0) return width to height
        }
        if (source.size >= 4 && source[0] == 0xFF.toByte() && source[1] == 0xD8.toByte()) {
            var index = 2
            while (index + 8 < source.size) {
                if (source[index] != 0xFF.toByte()) {
                    index++
                    continue
                }
                while (index < source.size && source[index] == 0xFF.toByte()) index++
                if (index >= source.size) break
                val marker = source[index].toInt() and 0xFF
                index++
                if (marker in JPEG_START_OF_FRAME && index + 6 < source.size) {
                    val height = source.unsignedShort(index + 3)
                    val width = source.unsignedShort(index + 5)
                    if (width > 0 && height > 0) return width to height
                }
                if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) continue
                if (index + 1 >= source.size) break
                val length = source.unsignedShort(index)
                if (length < 2 || index + length > source.size) break
                index += length
            }
        }
        val bounds = runCatching {
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(source, 0, source.size, this)
            }
        }.getOrNull()
        return (bounds?.outWidth ?: 1).coerceAtLeast(1) to (bounds?.outHeight ?: 1).coerceAtLeast(1)
    }

    private fun ByteArray.bigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.unsignedShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun documentXml(document: NoteDocument, images: List<ImagePart>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
        append(paragraphXml(document.title.ifBlank { "无标题" }, "Title"))
        if (document.subtitle.isNotBlank()) append(paragraphXml(document.subtitle, "Subtitle"))
        document.blocks.forEach { block ->
            when (block.type) {
                BlockType.TEXT -> append(richParagraphXml(block))
                BlockType.IMAGE -> images.firstOrNull { it.blockId == block.id }?.let { append(imageParagraphXml(it, block.imageCaption)) }
                BlockType.TABLE -> append(tableXml(block.tableCells))
                BlockType.DIVIDER -> append("""<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="808080"/></w:pBdr></w:pPr></w:p>""")
            }
        }
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""")
    }

    private fun richParagraphXml(block: NoteBlock): String = buildString {
        val style = when (block.textKind) {
            TextKind.TITLE -> "Title"
            TextKind.HEADING -> "Heading1"
            TextKind.SUBHEADING -> "Heading2"
            TextKind.QUOTE -> "Quote"
            TextKind.CHECKLIST, TextKind.BODY -> "Normal"
        }
        append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/>")
        if (block.textKind == TextKind.CHECKLIST) append("<w:ind w:left=\"360\"/>")
        append("</w:pPr>")
        if (block.textKind == TextKind.CHECKLIST) {
            append(runXml(if (block.checked) "☒ " else "☐ ", CombinedStyle()))
        }
        styledPieces(block).forEach { append(runXml(it.text, it.style)) }
        append("</w:p>")
    }

    private fun paragraphXml(text: String, style: String): String =
        "<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>${runXml(text, CombinedStyle())}</w:p>"

    private fun runXml(text: String, style: CombinedStyle): String = buildString {
        append("<w:r><w:rPr>")
        if (style.bold) append("<w:b/>")
        if (style.italic) append("<w:i/>")
        if (style.underline) append("<w:u w:val=\"single\"/>")
        if (style.strikeThrough) append("<w:strike/>")
        style.colorArgb?.let { append("<w:color w:val=\"").append(it.toString(16).takeLast(6).uppercase()).append("\"/>") }
        append("</w:rPr>")
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            if (index > 0) append("<w:br/>")
            append("<w:t xml:space=\"preserve\">").append(line.xml()).append("</w:t>")
        }
        append("</w:r>")
    }

    private fun imageParagraphXml(image: ImagePart, caption: String): String = buildString {
        append("""<w:p><w:r><w:drawing><wp:inline><wp:extent cx="${image.widthEmu}" cy="${image.heightEmu}"/><wp:docPr id="${image.documentPropertyId}" name="Image ${image.documentPropertyId}"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="${image.documentPropertyId}" name="${image.fileName}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="${image.relationshipId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${image.widthEmu}" cy="${image.heightEmu}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
        if (caption.isNotBlank()) append(paragraphXml(caption, "Caption"))
    }

    private fun tableXml(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        return buildString {
            append("<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>")
            rows.forEach { row ->
                append("<w:tr>")
                row.forEach { cell -> append("<w:tc>").append(paragraphXml(cell, "Normal")).append("</w:tc>") }
                append("</w:tr>")
            }
            append("</w:tbl><w:p/>")
        }
    }

    private data class StyledPiece(val text: String, val style: CombinedStyle)
    private data class CombinedStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikeThrough: Boolean = false,
        val colorArgb: Long? = null,
    )

    private fun styledPieces(block: NoteBlock): List<StyledPiece> {
        if (block.text.isEmpty()) return listOf(StyledPiece("", CombinedStyle()))
        val boundaries = buildSet {
            add(0)
            add(block.text.length)
            block.spans.forEach { span ->
                add(span.start.coerceIn(0, block.text.length))
                add(span.end.coerceIn(0, block.text.length))
            }
        }.sorted()
        return boundaries.zipWithNext().mapNotNull { (start, end) ->
            if (start >= end) return@mapNotNull null
            val active = block.spans.filter { it.start < end && it.end > start }
            StyledPiece(
                block.text.substring(start, end),
                CombinedStyle(
                    bold = active.any(RichSpan::bold),
                    italic = active.any(RichSpan::italic),
                    underline = active.any(RichSpan::underline),
                    strikeThrough = active.any(RichSpan::strikeThrough),
                    colorArgb = active.mapNotNull(RichSpan::colorArgb).lastOrNull(),
                ),
            )
        }
    }

    private fun contentTypes(imageExtensions: Set<String>): String {
        val imageTypes = buildString {
            if ("png" in imageExtensions) append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
            if ("jpg" in imageExtensions) append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>$imageTypes<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""
    }

    private fun documentRelationships(images: List<ImagePart>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        images.forEach { append("<Relationship Id=\"").append(it.relationshipId).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/").append(it.fileName).append("\"/>") }
        append("</Relationships>")
    }

    private const val rootRelationships = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""
    private const val coreProperties = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator>Kotj</dc:creator><dc:title>Kotj 备忘录</dc:title></cp:coreProperties>"""
    private const val appProperties = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Kotj</Application></Properties>"""
    private const val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="22"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="42"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:rPr><w:color w:val="666666"/><w:sz w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:rPr><w:b/><w:sz w:val="27"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:pPr><w:ind w:left="480"/></w:pPr><w:rPr><w:i/><w:color w:val="555555"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="Caption"/><w:rPr><w:i/><w:color w:val="666666"/><w:sz w:val="18"/></w:rPr></w:style></w:styles>"""

    private fun String.xml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun String.markdownEscaped(): String = buildString(length) {
        this@markdownEscaped.forEach { character ->
            if (character in "\\`*_{}[]<>()#+-.!|~") append('\\')
            append(character)
        }
    }
    private fun ZipOutputStream.textEntry(path: String, value: String) = binaryEntry(path, value.toByteArray(Charsets.UTF_8))
    private fun ZipOutputStream.binaryEntry(path: String, value: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(value)
        closeEntry()
    }
}
