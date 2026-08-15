package com.lopleec.kotj.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.edit
import com.lopleec.kotj.backup.DriveRestoreResult
import com.lopleec.kotj.model.Category
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.NoteSummary
import com.lopleec.kotj.model.StoredNote
import com.lopleec.kotj.security.NoteCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    fun hardenStorage() = synchronized(BACKUP_SNAPSHOT_LOCK) {
        val restoreArtifacts = appContext.filesDir.listFiles().orEmpty()
        val liveAttachments = File(appContext.filesDir, "attachments")
        val rollbackDirectories = restoreArtifacts.filter {
            it.isDirectory && RESTORE_ROLLBACK_NAME.matches(it.name)
        }
        if (!liveAttachments.exists() && rollbackDirectories.size == 1) {
            moveDirectory(rollbackDirectories.single(), liveAttachments)
        }
        restoreArtifacts
            .filter { it.isDirectory && RESTORE_DISPOSABLE_NAME.matches(it.name) }
            .forEach(::securelyDeleteDirectory)
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

    fun addCategory(name: String) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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

    fun renameCategory(id: String, name: String) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "分类名不能为空" }
        check(database.writableDatabase.update(
            "categories",
            ContentValues().apply { put("name", cleanName) },
            "id = ?",
            arrayOf(id),
        ) == 1) { "分类不存在" }
    }

    fun deleteCategory(id: String) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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

    fun encryptedNoteIds(): Set<String> = noteIds("is_encrypted = 1").toSet()

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

    fun createBlank(categoryId: String?): StoredNote = synchronized(BACKUP_SNAPSHOT_LOCK) {
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
        note
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
    ) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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

    fun cleanupAttachments(noteId: String, document: NoteDocument) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        attachmentStore.commitPrepared(noteId, document)
    }

    fun importImage(
        noteId: String,
        uri: Uri,
        encrypted: Boolean,
        password: String?,
    ): NoteBlock = synchronized(BACKUP_SNAPSHOT_LOCK) {
        attachmentStore.importImage(uri, noteId, encrypted, password)
    }

    fun readAttachment(block: NoteBlock, password: String?): AttachmentContent =
        attachmentStore.read(block, password)

    fun discardAttachment(block: NoteBlock) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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
    ): NoteDocument = synchronized(BACKUP_SNAPSHOT_LOCK) {
        val prepared = attachmentStore.prepareEncryption(document, noteId, targetEncrypted, password)
        var persisted = false
        try {
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

    fun setDeleted(id: String, deleted: Boolean) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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

    fun moveNote(id: String, categoryId: String?) = synchronized(BACKUP_SNAPSHOT_LOCK) {
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

    fun setPinned(id: String, pinned: Boolean) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        check(database.writableDatabase.update(
            "notes",
            ContentValues().apply {
                put("is_pinned", if (pinned) 1 else 0)
                // Pinning is user-visible note state and must participate in cross-device merges.
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id),
        ) == 1) { "备忘录不存在" }
    }

    fun renameNote(id: String, title: String) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        val stored = requireNotNull(readNote(id)) { "备忘录不存在" }
        require(!stored.encrypted) { "请先打开并解锁加密备忘录" }
        val document = decode(stored).copy(title = title.trim())
        saveDocument(id, stored.categoryId, document, encrypted = false, password = null)
    }

    fun deleteAny(id: String) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        database.writableDatabase.delete("notes", "id = ?", arrayOf(id))
        attachmentStore.deleteAll(id)
    }

    fun requireDeletionPassword(id: String, password: String?) {
        val stored = requireNotNull(readNote(id)) { "备忘录不存在" }
        if (!stored.encrypted) return
        require(!password.isNullOrEmpty()) { "请输入密码" }
        decode(stored, password)
    }

    fun moveToTrashAuthorized(id: String, password: String?) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        requireDeletionPassword(id, password)
        setDeleted(id, true)
    }

    fun deleteForeverAuthorized(id: String, password: String?) = synchronized(BACKUP_SNAPSHOT_LOCK) {
        requireDeletionPassword(id, password)
        check(database.writableDatabase.delete("notes", "id = ? AND is_deleted = 1", arrayOf(id)) == 1) {
            "备忘录不在最近删除中"
        }
        attachmentStore.deleteAll(id)
    }

    fun emptyTrashUnencrypted(): Int = synchronized(BACKUP_SNAPSHOT_LOCK) {
        val ids = noteIds("is_deleted = 1 AND is_encrypted = 0")
        val count = database.writableDatabase.delete("notes", "is_deleted = 1 AND is_encrypted = 0", null)
        ids.forEach(attachmentStore::deleteAll)
        count
    }

    fun encryptedTrashCount(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM notes WHERE is_deleted = 1 AND is_encrypted = 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun purgeExpiredTrash(retentionDays: Int = 30): List<String> {
        if (retentionDays <= 0) return emptyList()
        return synchronized(BACKUP_SNAPSHOT_LOCK) {
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
            ids
        }
    }

    /**
     * Writes a logical, restorable snapshot rather than copying the live SQLite file. This keeps
     * WAL state out of the backup and lets the outer backup cipher protect ordinary notes and
     * attachments that are intentionally plaintext in local-only mode.
     */
    fun writeBackupZip(output: OutputStream, storageMode: DriveStorageMode) {
        synchronized(BACKUP_SNAPSHOT_LOCK) {
            val attachmentDirectory = File(appContext.filesDir, "attachments")
            val attachments = attachmentDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && ATTACHMENT_FILE_NAME.matches(it.name) }
                .sortedBy(File::getName)
            val totalAttachmentBytes = attachments.sumOf(File::length)
            require(totalAttachmentBytes <= MAX_BACKUP_ATTACHMENT_BYTES) {
                "Attachments exceed the 1 GB backup limit"
            }

            val manifest = JSONObject().apply {
                put("format", "kotj-drive-backup")
                put("version", 1)
                put("createdAt", System.currentTimeMillis())
                put("storageMode", storageMode.name)
                put("databaseVersion", 2)
                put("categories", backupCategories())
                put("notes", backupNotes())
                put("attachments", JSONArray().apply {
                    attachments.forEach { file ->
                        put(JSONObject().apply {
                            put("name", file.name)
                            put("size", file.length())
                        })
                    }
                })
            }

            ZipOutputStream(output.buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                attachments.forEach { file ->
                    zip.putNextEntry(ZipEntry("attachments/${file.name}"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun restoreBackupZip(input: InputStream): DriveRestoreResult {
        val stagingDirectory = File(appContext.filesDir, ".restore-staging-${UUID.randomUUID()}")
        val archive = BackupRestoreArchive.extract(input, stagingDirectory)
        return try {
            synchronized(BACKUP_SNAPSHOT_LOCK) {
                installRestoredArchive(archive, stagingDirectory)
            }
        } finally {
            if (stagingDirectory.exists()) securelyDeleteDirectory(stagingDirectory)
        }
    }

    private fun installRestoredArchive(
        archive: RestoredArchive,
        stagingDirectory: File,
    ): DriveRestoreResult {
        val db = database.writableDatabase
        val localCategoryIds = db.query(
            "categories",
            arrayOf("id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.string("id")) } }
        val localNotes = db.query(
            "notes",
            arrayOf("id", "updated_at"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LocalMergeNote(cursor.string("id"), cursor.long("updated_at")))
                }
            }
        }
        val plan = BackupMergePlanner.plan(localCategoryIds, localNotes, archive)
        val liveAttachments = File(appContext.filesDir, "attachments")
        val rollbackAttachments = File(appContext.filesDir, ".restore-rollback-${UUID.randomUUID()}")
        val cloudWinningNoteIds = plan.cloudNotesToInstall.mapTo(hashSetOf(), RestoredNote::id)
        val movedLocalAttachments = mutableListOf<String>()
        val installedCloudAttachments = mutableListOf<String>()
        var mergeCommitted = false
        try {
            check(liveAttachments.exists() || liveAttachments.mkdirs()) {
                "Could not create the attachment directory"
            }
            liveAttachments.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile && ATTACHMENT_FILE_NAME.matches(file.name) &&
                        file.name.take(NOTE_ID_LENGTH) in cloudWinningNoteIds
                }
                .forEach { file ->
                    check(rollbackAttachments.exists() || rollbackAttachments.mkdirs()) {
                        "Could not create the attachment rollback directory"
                    }
                    moveFile(file, File(rollbackAttachments, file.name))
                    movedLocalAttachments += file.name
                }
            stagingDirectory.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile && ATTACHMENT_FILE_NAME.matches(file.name) &&
                        file.name.take(NOTE_ID_LENGTH) in cloudWinningNoteIds
                }
                .forEach { file ->
                    val target = File(liveAttachments, file.name)
                    require(!target.exists()) { "Cloud attachment conflicts with local storage" }
                    moveFile(file, target)
                    installedCloudAttachments += file.name
                }

            db.beginTransaction()
            try {
                plan.categoriesToInsert.forEach { category ->
                    db.insertOrThrow(
                        "categories",
                        null,
                        ContentValues().apply {
                            put("id", category.id)
                            put("name", category.name)
                            put("created_at", category.createdAt)
                        },
                    )
                }
                plan.cloudNotesToInstall.forEach { note ->
                    val values = valuesForRestoredNote(note)
                    if (note.id in plan.importedCloudNoteIds) {
                        db.insertOrThrow("notes", null, values)
                    } else {
                        check(db.update("notes", values, "id = ?", arrayOf(note.id)) == 1) {
                            "Local note disappeared during cloud merge"
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            mergeCommitted = true

            return DriveRestoreResult(
                noteCount = plan.finalNoteCount,
                categoryCount = plan.finalCategoryCount,
                attachmentCount = liveAttachments.listFiles().orEmpty().count { file ->
                    file.isFile && ATTACHMENT_FILE_NAME.matches(file.name)
                },
                backupCreatedAt = archive.createdAt,
                importedNoteCount = plan.importedNoteCount,
                updatedNoteCount = plan.updatedNoteCount,
                retainedLocalNoteCount = plan.retainedLocalNoteCount,
            )
        } catch (error: Throwable) {
            installedCloudAttachments.asReversed().forEach { name ->
                runCatching { securelyDeleteFile(File(liveAttachments, name)) }
                    .onFailure(error::addSuppressed)
            }
            movedLocalAttachments.asReversed().forEach { name ->
                val rollback = File(rollbackAttachments, name)
                if (!rollback.exists()) return@forEach
                runCatching { moveFile(rollback, File(liveAttachments, name)) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            if (mergeCommitted && rollbackAttachments.exists()) {
                runCatching { securelyDeleteDirectory(rollbackAttachments) }
            } else if (rollbackAttachments.isDirectory && rollbackAttachments.listFiles().isNullOrEmpty()) {
                runCatching { rollbackAttachments.delete() }
            }
        }
    }

    private fun valuesForRestoredNote(note: RestoredNote): ContentValues {
        val document = note.document
        return ContentValues().apply {
            put("id", note.id)
            if (note.categoryId == null) putNull("category_id") else put("category_id", note.categoryId)
            put("payload", note.payload)
            put("display_title", if (note.encrypted) "加密备忘录" else document!!.title.ifBlank { "无标题" })
            put("snippet", "")
            put("search_text", if (note.encrypted) "" else document!!.searchableText().lowercase(Locale.ROOT))
            put("is_encrypted", if (note.encrypted) 1 else 0)
            put("is_deleted", if (note.deleted) 1 else 0)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
            if (note.deletedAt == null) putNull("deleted_at") else put("deleted_at", note.deletedAt)
            put("is_pinned", if (note.pinned) 1 else 0)
        }
    }

    private fun moveFile(source: File, target: File) {
        require(source.isFile && !target.exists()) { "Invalid restore attachment state" }
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun securelyDeleteFile(file: File) {
        if (!file.exists() || !file.isFile) return
        RandomAccessFile(file, "rws").use { output ->
            var remaining = output.length()
            val zeros = ByteArray(64 * 1024)
            try {
                output.seek(0)
                while (remaining > 0L) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    output.write(zeros, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            } finally {
                zeros.fill(0)
            }
        }
        check(file.delete() || !file.exists()) { "Could not clean a restore attachment" }
    }

    private fun moveDirectory(source: File, target: File) {
        require(source.isDirectory && !target.exists()) { "Invalid restore directory state" }
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun securelyDeleteDirectory(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                runCatching {
                    RandomAccessFile(file, "rws").use { output ->
                        var remaining = output.length()
                        val zeros = ByteArray(64 * 1024)
                        try {
                            output.seek(0)
                            while (remaining > 0L) {
                                val count = minOf(remaining, zeros.size.toLong()).toInt()
                                output.write(zeros, 0, count)
                                remaining -= count
                            }
                            output.fd.sync()
                        } finally {
                            zeros.fill(0)
                        }
                    }
                }
            }
            check(file.delete() || !file.exists()) { "Could not clean the restore workspace" }
        }
    }

    private fun backupCategories(): JSONArray = database.readableDatabase.query(
        "categories",
        arrayOf("id", "name", "created_at"),
        null,
        null,
        null,
        null,
        "created_at ASC",
    ).use { cursor ->
        JSONArray().apply {
            while (cursor.moveToNext()) {
                put(JSONObject().apply {
                    put("id", cursor.string("id"))
                    put("name", cursor.string("name"))
                    put("createdAt", cursor.long("created_at"))
                })
            }
        }
    }

    private fun backupNotes(): JSONArray = database.readableDatabase.query(
        "notes",
        arrayOf(
            "id", "category_id", "payload", "is_encrypted", "is_deleted", "created_at",
            "updated_at", "deleted_at", "is_pinned",
        ),
        null,
        null,
        null,
        null,
        "created_at ASC",
    ).use { cursor ->
        JSONArray().apply {
            while (cursor.moveToNext()) {
                put(JSONObject().apply {
                    put("id", cursor.string("id"))
                    put("categoryId", cursor.nullableString("category_id") ?: JSONObject.NULL)
                    put("payload", cursor.string("payload"))
                    put("encrypted", cursor.int("is_encrypted") == 1)
                    put("deleted", cursor.int("is_deleted") == 1)
                    put("createdAt", cursor.long("created_at"))
                    put("updatedAt", cursor.long("updated_at"))
                    put("deletedAt", cursor.nullableLong("deleted_at") ?: JSONObject.NULL)
                    put("pinned", cursor.int("is_pinned") == 1)
                })
            }
        }
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

    private companion object {
        val BACKUP_SNAPSHOT_LOCK = Any()
        val ATTACHMENT_FILE_NAME = Regex("[0-9a-f-]{73}")
        val RESTORE_DISPOSABLE_NAME = Regex("\\.restore-(staging|failed)-[0-9a-f-]{36}")
        val RESTORE_ROLLBACK_NAME = Regex("\\.restore-rollback-[0-9a-f-]{36}")
        const val NOTE_ID_LENGTH = 36
        const val MAX_BACKUP_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
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
