package com.lopleec.kotj.model

import java.util.UUID

enum class BlockType { TEXT, IMAGE, TABLE, DIVIDER }

enum class TextKind { TITLE, BODY, HEADING, SUBHEADING, QUOTE, CHECKLIST }

data class RichSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val colorArgb: Long? = null,
)

data class NoteBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: BlockType = BlockType.TEXT,
    val text: String = "",
    val textKind: TextKind = TextKind.BODY,
    val checked: Boolean = false,
    val spans: List<RichSpan> = emptyList(),
    val imageUri: String? = null,
    val imageMimeType: String? = null,
    val imageEncrypted: Boolean = false,
    val imageCaption: String = "",
    val tableCells: List<List<String>> = emptyList(),
)

data class NoteDocument(
    val title: String = "",
    val subtitle: String = "",
    val blocks: List<NoteBlock> = emptyList(),
) {
    fun searchableText(): String = buildString {
        append(title).append('\n').append(subtitle)
        blocks.forEach { block ->
            append('\n').append(block.text)
            append('\n').append(block.imageCaption)
            block.tableCells.flatten().forEach { append('\n').append(it) }
        }
    }.trim()

    fun isMeaningfullyEmpty(): Boolean =
        title.isBlank() && subtitle.isBlank() && blocks.all { block ->
            when (block.type) {
                BlockType.TEXT -> block.text.isBlank()
                BlockType.IMAGE -> block.imageUri.isNullOrBlank()
                BlockType.TABLE -> block.tableCells.flatten().all(String::isBlank)
                BlockType.DIVIDER -> true
            }
        }
}

data class Category(
    val id: String,
    val name: String,
)

data class NoteSummary(
    val id: String,
    val categoryId: String?,
    val title: String,
    val searchText: String,
    val encrypted: Boolean,
    val updatedAt: Long,
    val deletedAt: Long?,
    val pinned: Boolean = false,
)

data class StoredNote(
    val id: String,
    val categoryId: String?,
    val payload: String,
    val encrypted: Boolean,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

data class EditorSession(
    val noteId: String,
    val categoryId: String?,
    val document: NoteDocument,
    val encrypted: Boolean,
    val password: String? = null,
    val pinned: Boolean = false,
    val autoFocus: Boolean = false,
    val initialSearchQuery: String = "",
)

data class ImportPreview(
    val sourceName: String,
    val document: NoteDocument,
)
