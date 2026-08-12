package com.lopleec.kotj.data

import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.RichSpan
import com.lopleec.kotj.model.TextKind
import org.json.JSONArray
import org.json.JSONObject

object NoteJson {
    fun encode(document: NoteDocument): String = JSONObject().apply {
        put("version", 4)
        put("title", document.title)
        put("subtitle", document.subtitle)
        put("blocks", JSONArray().apply {
            document.blocks.forEach { block ->
                put(JSONObject().apply {
                    put("id", block.id)
                    put("type", block.type.name)
                    put("text", block.text)
                    put("textKind", block.textKind.name)
                    put("checked", block.checked)
                    put("imageUri", block.imageUri ?: JSONObject.NULL)
                    put("imageMimeType", block.imageMimeType ?: JSONObject.NULL)
                    put("imageEncrypted", block.imageEncrypted)
                    put("imageCaption", block.imageCaption)
                    put("spans", JSONArray().apply {
                        block.spans.forEach { span ->
                            put(JSONObject().apply {
                                put("start", span.start)
                                put("end", span.end)
                                put("bold", span.bold)
                                put("italic", span.italic)
                                put("underline", span.underline)
                                put("strike", span.strikeThrough)
                                put("color", span.colorArgb ?: JSONObject.NULL)
                            })
                        }
                    })
                    put("table", JSONArray().apply {
                        block.tableCells.forEach { row ->
                            put(JSONArray().apply { row.forEach(::put) })
                        }
                    })
                })
            }
        })
    }.toString()

    fun decode(json: String): NoteDocument {
        val root = JSONObject(json)
        val version = root.optInt("version", 1)
        val blocksJson = root.optJSONArray("blocks") ?: JSONArray()
        val blocks = buildList {
            for (index in 0 until blocksJson.length()) {
                val item = blocksJson.getJSONObject(index)
                val spansJson = item.optJSONArray("spans") ?: JSONArray()
                val spans = buildList {
                    for (spanIndex in 0 until spansJson.length()) {
                        val span = spansJson.getJSONObject(spanIndex)
                        add(
                            RichSpan(
                                start = span.optInt("start"),
                                end = span.optInt("end"),
                                bold = span.optBoolean("bold"),
                                italic = span.optBoolean("italic"),
                                underline = span.optBoolean("underline"),
                                strikeThrough = span.optBoolean("strike"),
                                colorArgb = if (span.isNull("color")) null else span.optLong("color"),
                            ),
                        )
                    }
                }
                val tableJson = item.optJSONArray("table") ?: JSONArray()
                val table = buildList {
                    for (rowIndex in 0 until tableJson.length()) {
                        val row = tableJson.getJSONArray(rowIndex)
                        add(List(row.length()) { cellIndex -> row.optString(cellIndex) })
                    }
                }
                add(
                    NoteBlock(
                        id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        type = enumValueOrDefault(item.optString("type"), BlockType.TEXT),
                        text = item.optString("text"),
                        textKind = enumValueOrDefault(item.optString("textKind"), TextKind.BODY),
                        checked = item.optBoolean("checked", false),
                        spans = spans,
                        imageUri = if (item.isNull("imageUri")) null else item.optString("imageUri"),
                        imageMimeType = if (item.isNull("imageMimeType")) null else item.optString("imageMimeType"),
                        imageEncrypted = item.optBoolean("imageEncrypted", false),
                        imageCaption = item.optString("imageCaption"),
                        tableCells = table,
                    ),
                )
            }
        }
        val title = root.optString("title")
        val subtitle = root.optString("subtitle")
        val migratedBlocks = if (
            version < 2 && title.isBlank() && subtitle.isBlank() && blocks.size == 1 &&
            blocks.single().type == BlockType.TEXT && blocks.single().text.isBlank()
        ) emptyList() else blocks
        return NoteDocument(title = title, subtitle = subtitle, blocks = migratedBlocks)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}
