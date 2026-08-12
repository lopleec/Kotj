package com.lopleec.kotj.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.edit
import com.lopleec.kotj.model.Category
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.NoteSummary
import com.lopleec.kotj.model.StoredNote
import com.lopleec.kotj.security.NoteCipher
import java.util.Locale
import java.util.UUID

class NotesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = NotesDatabase(appContext)
    private val attachmentStore = AttachmentStore(appContext)
    private val securityPreferences = appContext.getSharedPreferences("security_state", Context.MODE_PRIVATE)

    /**
     * Secure deletion protects future row updates. The one-time VACUUM rebuild removes free pages
     * that may still contain plaintext written before secure deletion was enabled.
     */
    fun hardenStorage() {
        val db = database.writableDatabase
        enableSecureDelete(db)
        if (!securityPreferences.getBoolean("plaintext_pages_cleaned_v1", false)) {
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
            db.execSQL("VACUUM")
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            securityPreferences.edit(commit = true) { putBoolean("plaintext_pages_cleaned_v1", true) }
        }
        attachmentStore.cleanupOrphans(allNoteIds())
    }

    fun categories(): List<Category> = database.readableDatabase.query(
        "categories",
        arrayOf("id", "name"),
        null,
        null,
        null,
        null,
        "created_at ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Category(
                        id = cursor.string("id"),
                        name = cursor.string("name"),
                    ),
                )
            }
        }
    }

    fun addCategory(name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "分类名不能为空" }
        database.writableDatabase.insertOrThrow(
            "categories",
            null,
            ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("name", cleanName)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    fun renameCategory(id: String, name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "分类名不能为空" }
        check(database.writableDatabase.update(
            "categories",
            ContentValues().apply { put("name", cleanName) },
            "id = ?",
            arrayOf(id),
        ) == 1) { "分类不存在" }
    }

    fun deleteCategory(id: String) {
        database.writableDatabase.apply {
            beginTransaction()
            try {
                update(
                    "notes",
                    ContentValues().apply { putNull("category_id") },
                    "category_id = ?",
                    arrayOf(id),
                )
                check(delete("categories", "id = ?", arrayOf(id)) == 1) { "分类不存在" }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun listNotes(deleted: Boolean): List<NoteSummary> = database.readableDatabase.query(
        "notes",
        arrayOf(
            "id", "category_id", "display_title", "search_text", "is_encrypted",
            "updated_at", "deleted_at", "is_pinned",
        ),
        "is_deleted = ?",
        arrayOf(if (deleted) "1" else "0"),
        null,
        null,
        if (deleted) "deleted_at DESC" else "updated_at DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    NoteSummary(
                        id = cursor.string("id"),
                        categoryId = cursor.nullableString("category_id"),
                        title = cursor.string("display_title"),
                        searchText = cursor.string("search_text"),
                        encrypted = cursor.int("is_encrypted") == 1,
                        updatedAt = cursor.long("updated_at"),
                        deletedAt = cursor.nullableLong("deleted_at"),
                        pinned = cursor.int("is_pinned") == 1,
                    ),
                )
            }
        }
    }

    fun allNoteIds(): Set<String> = noteIds("1 = 1").toSet()

    fun readNote(id: String): StoredNote? = database.readableDatabase.query(
        "notes",
        arrayOf("id", "category_id", "payload", "is_encrypted", "is_deleted", "created_at", "updated_at", "is_pinned"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        StoredNote(
            id = cursor.string("id"),
            categoryId = cursor.nullableString("category_id"),
            payload = cursor.string("payload"),
            encrypted = cursor.int("is_encrypted") == 1,
            deleted = cursor.int("is_deleted") == 1,
            createdAt = cursor.long("created_at"),
            updatedAt = cursor.long("updated_at"),
            pinned = cursor.int("is_pinned") == 1,
        )
    }

    fun createBlank(categoryId: String?): StoredNote {
        val now = System.currentTimeMillis()
        val note = StoredNote(
            id = UUID.randomUUID().toString(),
            categoryId = categoryId,
            payload = NoteJson.encode(NoteDocument()),
            encrypted = false,
            deleted = false,
            createdAt = now,
            updatedAt = now,
            pinned = false,
        )
        database.writableDatabase.insertOrThrow(
            "notes",
            null,
            valuesForDocument(note, NoteDocument(), note.payload),
        )
        return note
    }

    fun decode(note: StoredNote, password: String? = null): NoteDocument {
        val json = if (note.encrypted) {
            require(!password.isNullOrEmpty()) { "需要密码" }
            NoteCipher.decrypt(note.payload, password.toCharArray())
        } else {
            note.payload
        }
        return NoteJson.decode(json)
    }

    fun saveDocument(
        noteId: String,
        categoryId: String?,
        document: NoteDocument,
        encrypted: Boolean,
        password: String?,
        cleanupAttachments: Boolean = true,
    ) {
        val current = requireNotNull(readNote(noteId)) { "备忘录不存在" }
        val json = NoteJson.encode(document)
        val payload = if (encrypted) {
            require(!password.isNullOrEmpty()) { "加密备忘录需要密码" }
            NoteCipher.encrypt(json, password.toCharArray())
        } else {
            json
        }
        val next = current.copy(
            categoryId = categoryId,
            payload = payload,
            encrypted = encrypted,
            updatedAt = System.currentTimeMillis(),
        )
        check(database.writableDatabase.update(
            "notes",
            valuesForDocument(next, document, payload),
            "id = ?",
            arrayOf(noteId),
        ) == 1) { "备忘录不存在" }
        if (cleanupAttachments) attachmentStore.commitPrepared(noteId, document)
    }

    fun cleanupAttachments(noteId: String, document: NoteDocument) {
        attachmentStore.commitPrepared(noteId, document)
    }

    fun importImage(
        noteId: String,
        uri: Uri,
        encrypted: Boolean,
        password: String?,
    ): NoteBlock = attachmentStore.importImage(uri, noteId, encrypted, password)

    fun readAttachment(block: NoteBlock, password: String?): AttachmentContent =
        attachmentStore.read(block, password)

    fun discardAttachment(block: NoteBlock) {
        attachmentStore.deleteBlock(block)
    }

    fun hasUnprotectedImages(document: NoteDocument): Boolean =
        attachmentStore.hasUnprotectedImages(document)

    fun changeEncryption(
        noteId: String,
        categoryId: String?,
        document: NoteDocument,
        targetEncrypted: Boolean,
        password: String?,
    ): NoteDocument {
        val prepared = attachmentStore.prepareEncryption(document, noteId, targetEncrypted, password)
        var persisted = false
        return try {
            saveDocument(
                noteId,
                categoryId,
                prepared.document,
                targetEncrypted,
                password,
                cleanupAttachments = false,
            )
            persisted = true
            attachmentStore.commitPrepared(noteId, prepared.document, document)
            prepared.document
        } catch (error: Throwable) {
            if (!persisted) attachmentStore.rollbackPrepared(prepared)
            throw error
        }
    }

    fun setDeleted(id: String, deleted: Boolean) {
        val now = System.currentTimeMillis()
        check(database.writableDatabase.update(
            "notes",
            ContentValues().apply {
                put("is_deleted", if (deleted) 1 else 0)
                if (deleted) put("deleted_at", now) else putNull("deleted_at")
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(id),
        ) == 1) { "备忘录不存在" }
    }

    fun moveNote(id: String, categoryId: String?) {
        check(database.writableDatabase.update(
            "notes",
            ContentValues().apply {
                if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id),
        ) == 1) { "备忘录不存在" }
    }

    fun setPinned(id: String, pinned: Boolean) {
        check(database.writableDatabase.update(
            "notes",
            ContentValues().apply { put("is_pinned", if (pinned) 1 else 0) },
            "id = ?",
            arrayOf(id),
        ) == 1) { "备忘录不存在" }
    }

    fun renameNote(id: String, title: String) {
        val stored = requireNotNull(readNote(id)) { "备忘录不存在" }
        require(!stored.encrypted) { "请先打开并解锁加密备忘录" }
        val document = decode(stored).copy(title = title.trim())
        saveDocument(id, stored.categoryId, document, encrypted = false, password = null)
    }

    fun deleteAny(id: String) {
        database.writableDatabase.delete("notes", "id = ?", arrayOf(id))
        attachmentStore.deleteAll(id)
    }

    fun requireDeletionPassword(id: String, password: String?) {
        val stored = requireNotNull(readNote(id)) { "备忘录不存在" }
        if (!stored.encrypted) return
        require(!password.isNullOrEmpty()) { "请输入密码" }
        decode(stored, password)
    }

    fun moveToTrashAuthorized(id: String, password: String?) {
        requireDeletionPassword(id, password)
        setDeleted(id, true)
    }

    fun deleteForeverAuthorized(id: String, password: String?) {
        requireDeletionPassword(id, password)
        check(database.writableDatabase.delete("notes", "id = ? AND is_deleted = 1", arrayOf(id)) == 1) {
            "备忘录不在最近删除中"
        }
        attachmentStore.deleteAll(id)
    }

    fun emptyTrashUnencrypted(): Int {
        val ids = noteIds("is_deleted = 1 AND is_encrypted = 0")
        val count = database.writableDatabase.delete("notes", "is_deleted = 1 AND is_encrypted = 0", null)
        ids.forEach(attachmentStore::deleteAll)
        return count
    }

    fun encryptedTrashCount(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM notes WHERE is_deleted = 1 AND is_encrypted = 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun purgeExpiredTrash(retentionDays: Int = 30): List<String> {
        if (retentionDays <= 0) return emptyList()
        val threshold = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        // The user already authorized moving encrypted notes to trash. Retention expiry is automatic
        // cleanup, so it must not prompt for credentials again.
        val selection = "is_deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < ?"
        val args = arrayOf(threshold.toString())
        val ids = noteIds(selection, args)
        database.writableDatabase.delete(
            "notes",
            selection,
            args,
        )
        ids.forEach(attachmentStore::deleteAll)
        return ids
    }

    private fun noteIds(selection: String, args: Array<String>? = null): List<String> =
        database.readableDatabase.query("notes", arrayOf("id"), selection, args, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.string("id")) }
        }

    private fun valuesForDocument(note: StoredNote, document: NoteDocument, payload: String): ContentValues =
        ContentValues().apply {
            put("id", note.id)
            if (note.categoryId == null) putNull("category_id") else put("category_id", note.categoryId)
            put("payload", payload)
            put("display_title", if (note.encrypted) "加密备忘录" else document.title.ifBlank { "无标题" })
            put("snippet", "")
            put(
                "search_text",
                if (note.encrypted) "" else document.searchableText().lowercase(Locale.ROOT),
            )
            put("is_encrypted", if (note.encrypted) 1 else 0)
            put("is_deleted", if (note.deleted) 1 else 0)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
            put("is_pinned", if (note.pinned) 1 else 0)
            if (!note.deleted) putNull("deleted_at")
        }
}

private class NotesDatabase(context: Context) : SQLiteOpenHelper(context, "kotj.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        enableSecureDelete(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE categories (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE notes (
                id TEXT PRIMARY KEY NOT NULL,
                category_id TEXT,
                payload TEXT NOT NULL,
                display_title TEXT NOT NULL,
                snippet TEXT NOT NULL,
                search_text TEXT NOT NULL,
                is_encrypted INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX notes_updated_index ON notes(is_deleted, updated_at DESC)")
        db.execSQL("CREATE INDEX notes_category_index ON notes(category_id, is_deleted)")
        val now = System.currentTimeMillis()
        listOf(
            Triple("personal", "个人", now),
            Triple("work", "工作", now + 1),
            Triple("ideas", "灵感", now + 2),
        ).forEach { (id, name, createdAt) ->
            db.insert(
                "categories",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("name", name)
                    put("created_at", createdAt)
                },
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
        }
    }
}

private fun enableSecureDelete(db: SQLiteDatabase) {
    db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
        check(cursor.moveToFirst() && cursor.getInt(0) != 0) { "无法启用数据库安全删除" }
    }
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
private fun Cursor.nullableString(column: String): String? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun Cursor.nullableLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }
