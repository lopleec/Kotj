package com.lopleec.kotj.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.security.AttachmentCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class AttachmentContent(val bytes: ByteArray, val mimeType: String)

data class PreparedAttachments(
    val document: NoteDocument,
    internal val createdFiles: List<String>,
)

class AttachmentStore(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "attachments")

    fun importImage(uri: Uri, noteId: String, encrypted: Boolean, password: String?): NoteBlock {
        val bytes = resolver.openInputStream(uri)?.use { it.readLimited(MAX_IMAGE_BYTES) }
            ?: error("无法读取图片")
        try {
            validateImage(bytes)
            val mimeType = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: inferMimeType(bytes)
            val fileName = newFileName(noteId)
            val stored = if (encrypted) {
                require(!password.isNullOrEmpty()) { "加密附件需要密码" }
                AttachmentCipher.encrypt(bytes, password.toCharArray(), fileName)
            } else {
                bytes
            }
            atomicWrite(fileName, stored)
            if (stored !== bytes) stored.fill(0)
            return NoteBlock(
                type = BlockType.IMAGE,
                imageUri = internalRef(fileName),
                imageMimeType = mimeType,
                imageEncrypted = encrypted,
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun prepareEncryption(
        document: NoteDocument,
        noteId: String,
        targetEncrypted: Boolean,
        password: String?,
    ): PreparedAttachments {
        if (targetEncrypted) require(!password.isNullOrEmpty()) { "加密附件需要密码" }
        val created = mutableListOf<String>()
        return try {
            val nextBlocks = document.blocks.map { block ->
                if (block.type != BlockType.IMAGE || block.imageUri.isNullOrBlank()) return@map block
                val currentName = internalName(block.imageUri)
                if (currentName != null && block.imageEncrypted == targetEncrypted) return@map block
                val plainBytes = read(block, password).bytes
                try {
                    val fileName = newFileName(noteId)
                    val stored = if (targetEncrypted) {
                        AttachmentCipher.encrypt(plainBytes, password!!.toCharArray(), fileName)
                    } else {
                        plainBytes
                    }
                    atomicWrite(fileName, stored)
                    if (stored !== plainBytes) stored.fill(0)
                    created += fileName
                    block.copy(
                        imageUri = internalRef(fileName),
                        imageMimeType = block.imageMimeType ?: inferMimeType(plainBytes),
                        imageEncrypted = targetEncrypted,
                    )
                } finally {
                    plainBytes.fill(0)
                }
            }
            PreparedAttachments(document.copy(blocks = nextBlocks), created)
        } catch (error: Throwable) {
            created.forEach(::deleteFile)
            throw error
        }
    }

    fun read(block: NoteBlock, password: String?): AttachmentContent {
        val reference = requireNotNull(block.imageUri) { "图片引用不存在" }
        val storedBytes = internalName(reference)?.let { fileName ->
            fileFor(fileName).inputStream().use { it.readLimited(MAX_STORED_IMAGE_BYTES) }
        } ?: resolver.openInputStream(reference.toUri())?.use { it.readLimited(MAX_IMAGE_BYTES) }
            ?: error("无法读取图片")
        val bytes = if (block.imageEncrypted) {
            try {
                require(!password.isNullOrEmpty()) { "加密附件需要密码" }
                val fileName = requireNotNull(internalName(reference)) { "不支持的加密附件引用" }
                AttachmentCipher.decrypt(storedBytes, password.toCharArray(), fileName)
            } finally {
                storedBytes.fill(0)
            }
        } else {
            storedBytes
        }
        return try {
            require(bytes.size <= MAX_IMAGE_BYTES) { "图片超过 25 MB 限制" }
            validateImage(bytes)
            AttachmentContent(bytes, block.imageMimeType ?: inferMimeType(bytes))
        } catch (error: Throwable) {
            bytes.fill(0)
            throw error
        }
    }

    fun commitPrepared(noteId: String, document: NoteDocument, previous: NoteDocument? = null) {
        deleteExcept(noteId, document)
        previous?.blocks.orEmpty()
            .asSequence()
            .filter { it.type == BlockType.IMAGE }
            .mapNotNull { it.imageUri }
            .filterNot { it.startsWith(INTERNAL_PREFIX) }
            .filterNot { legacy -> document.blocks.any { it.imageUri == legacy } }
            .forEach { legacy ->
                runCatching {
                    resolver.releasePersistableUriPermission(legacy.toUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
    }

    fun rollbackPrepared(prepared: PreparedAttachments) {
        prepared.createdFiles.forEach(::deleteFile)
    }

    fun deleteBlock(block: NoteBlock) {
        internalName(block.imageUri)?.let(::deleteFile)
    }

    fun hasUnprotectedImages(document: NoteDocument): Boolean = document.blocks.any { block ->
        block.type == BlockType.IMAGE && !block.imageUri.isNullOrBlank() &&
            (internalName(block.imageUri) == null || !block.imageEncrypted)
    }

    fun deleteAll(noteId: String) {
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$noteId-") }
            ?.forEach(::secureDelete)
    }

    fun cleanupOrphans(validNoteIds: Set<String>) {
        directory.listFiles()?.filter(File::isFile)?.forEach { file ->
            val owner = file.name.take(36).takeIf(UUID_PATTERN::matches)
            if (owner == null || owner !in validNoteIds) secureDelete(file)
        }
    }

    private fun deleteExcept(noteId: String, document: NoteDocument) {
        val retained = document.blocks.mapNotNull { internalName(it.imageUri) }.toSet()
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$noteId-") && it.name !in retained }
            ?.forEach(::secureDelete)
    }

    private fun atomicWrite(fileName: String, bytes: ByteArray) {
        check(directory.exists() || directory.mkdirs()) { "无法创建附件目录" }
        val target = fileFor(fileName)
        val temporary = File(directory, ".$fileName.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) secureDelete(temporary)
        }
    }

    private fun fileFor(fileName: String): File {
        require(FILE_NAME.matches(fileName)) { "非法附件引用" }
        return File(directory, fileName)
    }

    private fun deleteFile(fileName: String) {
        runCatching { secureDelete(fileFor(fileName)) }
    }

    private fun secureDelete(file: File) {
        if (!file.exists() || !file.isFile) return
        runCatching {
            RandomAccessFile(file, "rws").use { output ->
                var remaining = output.length()
                output.seek(0)
                val zeros = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    output.write(zeros, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
        }
        file.delete()
    }

    private fun newFileName(noteId: String): String {
        require(UUID_PATTERN.matches(noteId)) { "非法备忘录 ID" }
        return "$noteId-${UUID.randomUUID()}"
    }

    private fun internalName(reference: String?): String? = reference
        ?.takeIf { it.startsWith(INTERNAL_PREFIX) }
        ?.removePrefix(INTERNAL_PREFIX)
        ?.also { require(FILE_NAME.matches(it)) { "非法附件引用" } }

    private fun internalRef(fileName: String): String = INTERNAL_PREFIX + fileName

    private fun validateImage(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "文件不是有效图片" }
    }

    private fun inferMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "image/png"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) in setOf("RIFF", "GIF8") ->
            if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") "image/webp" else "image/gif"
        else -> "image/png"
    }

    companion object {
        const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        private const val MAX_STORED_IMAGE_BYTES = MAX_IMAGE_BYTES + 64
        private const val INTERNAL_PREFIX = "kotj-attachment:"
        private val FILE_NAME = Regex("[0-9a-f-]{73}")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

internal fun InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB 限制" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
