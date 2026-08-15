package com.lopleec.kotj.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.Identity
import com.lopleec.kotj.backup.DriveAuthorizationPurpose
import com.lopleec.kotj.backup.DriveBackupEngine
import com.lopleec.kotj.backup.DriveBackupScheduler
import com.lopleec.kotj.backup.DriveBackupUiState
import com.lopleec.kotj.backup.GoogleDriveAuthorization
import com.lopleec.kotj.data.NotesRepository
import com.lopleec.kotj.data.AttachmentContent
import com.lopleec.kotj.data.AppSettings
import com.lopleec.kotj.data.SettingsRepository
import com.lopleec.kotj.model.Category
import com.lopleec.kotj.model.EditorSession
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteSummary
import com.lopleec.kotj.model.ImportPreview
import com.lopleec.kotj.importer.NoteImporter
import com.lopleec.kotj.security.SystemUnlockStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class NotesUiState(
    val categories: List<Category> = emptyList(),
    val notes: List<NoteSummary> = emptyList(),
    val selectedCategoryId: String? = null,
    val showingTrash: Boolean = false,
    val query: String = "",
    val editor: EditorSession? = null,
    val unlockNoteId: String? = null,
    val unlockWithSystem: Boolean = false,
    val pendingOpenQuery: String = "",
    val loading: Boolean = true,
    val message: String? = null,
    val settings: AppSettings = AppSettings(),
    val importPreview: ImportPreview? = null,
    val securityOperationInProgress: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val driveBackup: DriveBackupUiState = DriveBackupUiState(),
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotesRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val systemUnlockStore = SystemUnlockStore(application)
    private var saveJob: Job? = null
    private var autoLockJob: Job? = null
    private var createJob: Job? = null
    private var openJob: Job? = null
    private var importJob: Job? = null
    private var saveImportJob: Job? = null
    private var refreshGeneration = 0L
    private var pendingDriveAccountEmail: String? = null
    private var pendingDriveAuthorizationPurpose = DriveAuthorizationPurpose.BACKUP
    private var driveAuthorizationPending = false
    private val undoHistory = ArrayDeque<NoteDocument>()
    private val redoHistory = ArrayDeque<NoteDocument>()

    var state by mutableStateOf(NotesUiState(settings = settingsRepository.load()))
        private set

    init {
        if (state.settings.googleDriveBackupEnabled) {
            DriveBackupScheduler.configure(application, enabled = true)
            // Existing password-era installations already have the usable local key. An immediate
            // run publishes its account recovery record before the user considers reinstalling.
            DriveBackupScheduler.enqueueNow(application)
        }
        viewModelScope.launch {
            val hardeningError = withContext(Dispatchers.IO) {
                runCatching {
                    DriveBackupEngine(application).cleanupAbandonedRestoreWorkspace()
                    repository.hardenStorage()
                    repository.purgeExpiredTrash(state.settings.trashRetentionDays)
                        .forEach(systemUnlockStore::remove)
                    systemUnlockStore.cleanupOrphans(repository.encryptedNoteIds())
                }.exceptionOrNull()
            }
            refresh()
            if (hardeningError != null) {
                state = state.copy(
                    message = localizedError(
                        hardeningError,
                        "安全存储清理失败，请检查设备存储空间",
                        "Secure storage cleanup failed. Check available device storage",
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        resetPendingDriveAuthorization()
        super.onCleared()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val showingTrash = state.showingTrash
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.categories() to repository.listNotes(showingTrash)
                }
            }.onSuccess { result ->
                if (generation == refreshGeneration && showingTrash == state.showingTrash) {
                    state = state.copy(categories = result.first, notes = result.second, loading = false)
                    DriveBackupScheduler.onLocalDataChanged(getApplication())
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (generation == refreshGeneration) {
                    state = state.copy(
                        loading = false,
                        message = localizedError(error, "无法读取备忘录", "Could not load notes"),
                    )
                }
            }
        }
    }

    fun selectAll() {
        state = state.copy(selectedCategoryId = null, showingTrash = false, query = "", loading = true)
        refresh()
    }

    fun selectCategory(id: String) {
        state = state.copy(selectedCategoryId = id, showingTrash = false, query = "", loading = true)
        refresh()
    }

    fun showTrash() {
        state = state.copy(selectedCategoryId = null, showingTrash = true, query = "", loading = true)
        refresh()
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun openExternalIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != ContentResolver.SCHEME_CONTENT) return
        if (importJob?.isActive == true) return
        val application = getApplication<Application>()
        val editorSnapshot = state.editor
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        importJob = viewModelScope.launch {
            previousSave?.join()
            val result = runCatching {
                val preview = withContext(Dispatchers.IO) {
                    val imported = NoteImporter.read(
                        resolver = application.contentResolver,
                        uri = uri,
                        mimeType = intent.type ?: application.contentResolver.getType(uri),
                    )
                    if (editorSnapshot != null) {
                        if (editorSnapshot.document.isMeaningfullyEmpty()) {
                            repository.deleteAny(editorSnapshot.noteId)
                        } else {
                            persistSnapshot(editorSnapshot, cleanupAttachments = true)
                        }
                    }
                    imported
                }
                preview
            }
            result.onSuccess { preview ->
                if (editorSnapshot?.document?.isMeaningfullyEmpty() == true) {
                    systemUnlockStore.remove(editorSnapshot.noteId)
                }
                resetHistory()
                state = state.copy(
                    editor = null,
                    importPreview = preview,
                    message = null,
                    securityOperationInProgress = false,
                    canUndo = false,
                    canRedo = false,
                )
            }.onFailure { error ->
                if (error is CancellationException) return@launch
                state = state.copy(
                    message = localizedError(error, "无法打开文件", "Could not open the file"),
                    securityOperationInProgress = false,
                )
            }
        }
    }

    fun dismissImportPreview() {
        if (state.securityOperationInProgress) return
        state = state.copy(importPreview = null)
    }

    fun saveImportPreview() {
        if (saveImportJob?.isActive == true || state.securityOperationInProgress) return
        val preview = state.importPreview ?: return
        val categoryId = state.selectedCategoryId
        state = state.copy(securityOperationInProgress = true)
        saveImportJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stored = repository.createBlank(categoryId)
                    runCatching {
                        repository.saveDocument(
                            noteId = stored.id,
                            categoryId = categoryId,
                            document = preview.document,
                            encrypted = false,
                            password = null,
                        )
                    }.onFailure { repository.deleteAny(stored.id) }.getOrThrow()
                }
            }.onSuccess {
                state = state.copy(
                    importPreview = null,
                    message = tr("已保存为备忘录", "Saved as a note"),
                    securityOperationInProgress = false,
                )
                refresh()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(
                    message = localizedError(error, "保存文件失败", "Could not save the file"),
                    securityOperationInProgress = false,
                )
            }
        }
    }

    fun createNote() {
        if (createJob?.isActive == true || state.editor != null || state.securityOperationInProgress) return
        val categoryId = state.selectedCategoryId
        createJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.createBlank(categoryId) } }
                .onSuccess { note ->
                    state = state.copy(
                        editor = EditorSession(
                            noteId = note.id,
                            categoryId = note.categoryId,
                            document = NoteDocument(blocks = listOf(NoteBlock())),
                            encrypted = false,
                            autoFocus = true,
                        ),
                        canUndo = false,
                        canRedo = false,
                    )
                    resetHistory()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    state = state.copy(message = localizedError(error, "无法新建备忘录", "Could not create note"))
                }
        }
    }

    fun requestOpen(note: NoteSummary) {
        if (state.securityOperationInProgress) return
        if (note.encrypted) {
            state = state.copy(
                unlockNoteId = note.id,
                unlockWithSystem = shouldUseSystemUnlock(note.id),
                pendingOpenQuery = state.query.trim(),
            )
        } else {
            openNote(note.id, null, state.query.trim())
        }
    }

    fun unlock(password: String) {
        val id = state.unlockNoteId ?: return
        openNote(id, password, state.pendingOpenQuery)
    }

    fun dismissUnlock() {
        openJob?.cancel()
        state = state.copy(unlockNoteId = null, unlockWithSystem = false, pendingOpenQuery = "")
    }

    fun shouldUseSystemUnlock(noteId: String): Boolean =
        systemUnlockStore.hasPassword(noteId) &&
            (state.settings.useSystemUnlock || systemUnlockStore.isSystemOnly(noteId))

    private fun openNote(id: String, password: String?, initialSearchQuery: String = "") {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stored = requireNotNull(repository.readNote(id)) { tr("备忘录不存在", "Note not found") }
                    val decoded = repository.decode(stored, password)
                    val document = if (
                        stored.encrypted && repository.hasUnprotectedImages(decoded)
                    ) {
                        repository.changeEncryption(
                            noteId = id,
                            categoryId = stored.categoryId,
                            document = decoded,
                            targetEncrypted = true,
                            password = password,
                        )
                    } else {
                        decoded
                    }
                    repository.cleanupAttachments(id, document)
                    EditorSession(
                        noteId = id,
                        categoryId = stored.categoryId,
                        document = normalizeEditorFlow(document),
                        encrypted = stored.encrypted,
                        password = password,
                        pinned = stored.pinned,
                        autoFocus = false,
                        initialSearchQuery = initialSearchQuery,
                    )
                }
            }.onSuccess { editor ->
                state = state.copy(
                    editor = editor,
                    unlockNoteId = null,
                    unlockWithSystem = false,
                    pendingOpenQuery = "",
                    message = null,
                    canUndo = false,
                    canRedo = false,
                )
                resetHistory()
            }.onFailure { error ->
                if (error is CancellationException) return@launch
                state = state.copy(message = localizedError(error, "无法打开备忘录", "Could not open the note"))
            }
        }
    }

    fun updateDocument(transform: (NoteDocument) -> NoteDocument) {
        if (state.securityOperationInProgress) return
        val editor = state.editor ?: return
        val changed = transform(editor.document)
        if (changed == editor.document) return
        recordUndo(editor.document)
        state = state.copy(
            editor = editor.copy(document = changed),
            canUndo = undoHistory.isNotEmpty(),
            canRedo = false,
        )
        scheduleSave()
    }

    fun undoEditor() {
        val editor = state.editor ?: return
        val previous = undoHistory.removeLastOrNull() ?: return
        redoHistory.addLast(editor.document)
        state = state.copy(
            editor = editor.copy(document = previous),
            canUndo = undoHistory.isNotEmpty(),
            canRedo = true,
        )
        scheduleSave()
    }

    fun redoEditor() {
        val editor = state.editor ?: return
        val next = redoHistory.removeLastOrNull() ?: return
        undoHistory.addLast(editor.document)
        state = state.copy(
            editor = editor.copy(document = next),
            canUndo = true,
            canRedo = redoHistory.isNotEmpty(),
        )
        scheduleSave()
    }

    fun toggleEditorPinned() {
        val editor = state.editor ?: return
        val pinned = !editor.pinned
        state = state.copy(editor = editor.copy(pinned = pinned))
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.setPinned(editor.noteId, pinned) } }
                .onSuccess { refreshSummariesKeepingEditor() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val current = state.editor
                    if (current?.noteId == editor.noteId) {
                        state = state.copy(
                            editor = current.copy(pinned = editor.pinned),
                            message = localizedError(error, "无法更新置顶状态", "Could not update pin status"),
                        )
                    }
                }
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.setPinned(id, pinned) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    state = state.copy(message = localizedError(error, "无法更新置顶状态", "Could not update pin status"))
                }
            refresh()
        }
    }

    fun setEditorCategory(categoryId: String?) {
        if (state.securityOperationInProgress) return
        val editor = state.editor ?: return
        state = state.copy(editor = editor.copy(categoryId = categoryId))
        scheduleSave()
    }

    fun encryptEditor(password: String) {
        encryptEditor(password) {}
    }

    fun encryptEditor(password: String, onComplete: (Boolean) -> Unit) {
        if (password.length < 4) {
            state = state.copy(message = tr("密码至少需要 4 位", "Password must contain at least 4 characters"))
            onComplete(false)
            return
        }
        val editor = state.editor ?: run {
            onComplete(false)
            return
        }
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        viewModelScope.launch {
            previousSave?.join()
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.changeEncryption(
                        noteId = editor.noteId,
                        categoryId = editor.categoryId,
                        document = editor.document,
                        targetEncrypted = true,
                        password = password,
                    )
                }
            }.onSuccess { protectedDocument ->
                val current = state.editor
                resetHistory()
                state = state.copy(
                    editor = current?.takeIf { it.noteId == editor.noteId }?.copy(
                        document = protectedDocument,
                        encrypted = true,
                        password = password,
                    ),
                    message = tr("备忘录已加密", "Note encrypted"),
                    securityOperationInProgress = false,
                    canUndo = false,
                    canRedo = false,
                )
                refreshSummariesKeepingEditor()
                onComplete(true)
            }.onFailure { error ->
                state = state.copy(
                    message = localizedError(error, "加密失败", "Could not encrypt the note"),
                    securityOperationInProgress = false,
                )
                onComplete(false)
            }
        }
    }

    fun removeEncryption() {
        val editor = state.editor ?: return
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        viewModelScope.launch {
            previousSave?.join()
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.changeEncryption(
                        noteId = editor.noteId,
                        categoryId = editor.categoryId,
                        document = editor.document,
                        targetEncrypted = false,
                        password = editor.password,
                    )
                }
            }.onSuccess { plainDocument ->
                systemUnlockStore.remove(editor.noteId)
                val current = state.editor
                resetHistory()
                state = state.copy(
                    editor = current?.takeIf { it.noteId == editor.noteId }?.copy(
                        document = plainDocument,
                        encrypted = false,
                        password = null,
                    ),
                    message = tr("已移除密码", "Password removed"),
                    securityOperationInProgress = false,
                    canUndo = false,
                    canRedo = false,
                )
                refreshSummariesKeepingEditor()
            }.onFailure { error ->
                state = state.copy(
                    message = localizedError(error, "移除密码失败", "Could not remove the password"),
                    securityOperationInProgress = false,
                )
            }
        }
    }

    fun addImage(uri: Uri, afterBlockId: String?) {
        if (state.securityOperationInProgress) return
        val editor = state.editor ?: return
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.importImage(editor.noteId, uri, editor.encrypted, editor.password)
                }
            }
            result.onSuccess { block ->
                val current = state.editor
                if (current == null || current.noteId != editor.noteId) {
                    withContext(Dispatchers.IO) { repository.discardAttachment(block) }
                    return@onSuccess
                }
                val insertionIndex = when {
                    afterBlockId == "__note_title__" -> 0
                    else -> current.document.blocks.indexOfFirst { it.id == afterBlockId }
                        .let { if (it < 0) current.document.blocks.size else it + 1 }
                }
                val document = current.document.copy(
                    blocks = current.document.blocks.toMutableList().apply {
                        add(insertionIndex, block)
                        add(insertionIndex + 1, NoteBlock(type = com.lopleec.kotj.model.BlockType.TEXT))
                    },
                )
                recordUndo(current.document)
                state = state.copy(
                    editor = current.copy(document = document),
                    canUndo = true,
                    canRedo = false,
                )
                scheduleSave()
            }.onFailure { error ->
                state = state.copy(message = localizedError(error, "无法添加图片", "Could not add the photo"))
            }
        }
    }

    fun readAttachment(block: com.lopleec.kotj.model.NoteBlock, password: String?): AttachmentContent =
        repository.readAttachment(block, password)

    fun closeEditor() {
        if (state.securityOperationInProgress) return
        val snapshot = state.editor ?: return
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        viewModelScope.launch {
            previousSave?.join()
            val empty = snapshot.document.isMeaningfullyEmpty()
            runCatching {
                if (empty) {
                    withContext(Dispatchers.IO) { repository.deleteAny(snapshot.noteId) }
                } else {
                    val cleaned = snapshot.copy(
                        document = snapshot.document.copy(
                            blocks = snapshot.document.blocks.filterNot { block ->
                                block.type == com.lopleec.kotj.model.BlockType.TEXT && block.text.isBlank()
                            },
                        ),
                    )
                    persistSnapshot(cleaned, cleanupAttachments = true)
                }
            }.onSuccess {
                if (empty) systemUnlockStore.remove(snapshot.noteId)
                resetHistory()
                state = state.copy(
                    editor = null,
                    securityOperationInProgress = false,
                    canUndo = false,
                    canRedo = false,
                )
                refresh()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(
                    securityOperationInProgress = false,
                    message = localizedError(error, "无法保存备忘录", "Could not save note"),
                )
            }
        }
    }

    fun moveEditorToTrash(password: String?) {
        if (state.securityOperationInProgress) return
        val editor = state.editor ?: return
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        viewModelScope.launch {
            previousSave?.join()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (editor.encrypted) {
                        require(!password.isNullOrEmpty() && password == editor.password) { "密码错误" }
                    }
                    repository.saveDocument(
                        noteId = editor.noteId,
                        categoryId = editor.categoryId,
                        document = editor.document,
                        encrypted = editor.encrypted,
                        password = editor.password,
                    )
                    repository.setDeleted(editor.noteId, true)
                }
            }
            result.onSuccess {
                resetHistory()
                state = state.copy(
                    editor = null,
                    message = tr("已移到最近删除", "Moved to recently deleted"),
                    securityOperationInProgress = false,
                    canUndo = false,
                    canRedo = false,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(
                    message = localizedError(error, "密码错误，未删除备忘录", "Incorrect password. The note was not deleted"),
                    securityOperationInProgress = false,
                )
            }
            refresh()
        }
    }

    fun restore(id: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.setDeleted(id, false) } }
                .onSuccess { state = state.copy(message = tr("备忘录已恢复", "Note restored")) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    state = state.copy(message = localizedError(error, "无法恢复备忘录", "Could not restore note"))
                }
            refresh()
        }
    }

    fun moveNoteToTrash(id: String, password: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.moveToTrashAuthorized(id, password) } }
                .onSuccess {
                    onSuccess()
                    state = state.copy(message = tr("已移到最近删除", "Moved to recently deleted"))
                }
                .onFailure { error ->
                    state = state.copy(message = localizedError(error, "密码错误，未删除备忘录", "Incorrect password. The note was not deleted"))
                }
            refresh()
        }
    }

    fun moveNote(id: String, categoryId: String?) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.moveNote(id, categoryId) } }
                .onSuccess { state = state.copy(message = tr("已移动到分组", "Moved to group")) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    state = state.copy(message = localizedError(error, "无法移动备忘录", "Could not move note"))
                }
            refresh()
        }
    }

    fun renameNote(id: String, title: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.renameNote(id, title) } }
                .onSuccess { state = state.copy(message = tr("标题已修改", "Title updated")) }
                .onFailure { state = state.copy(message = localizedError(it, "重命名失败", "Could not rename the note")) }
            refresh()
        }
    }

    fun updateSettings(settings: AppSettings) {
        val previousSettings = state.settings
        settingsRepository.save(settings)
        state = state.copy(settings = settings)
        if (previousSettings.googleDriveBackupEnabled != settings.googleDriveBackupEnabled) {
            DriveBackupScheduler.configure(getApplication(), settings.googleDriveBackupEnabled)
            if (settings.googleDriveBackupEnabled) {
                refreshDriveBackupState()
                DriveBackupScheduler.enqueueNow(getApplication())
            }
        } else if (
            settings.googleDriveBackupEnabled &&
            previousSettings.driveStorageMode != settings.driveStorageMode
        ) {
            DriveBackupScheduler.enqueueNow(getApplication())
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.purgeExpiredTrash(settings.trashRetentionDays)
                        .forEach(systemUnlockStore::remove)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(message = localizedError(error, "无法清理最近删除", "Could not clean recently deleted"))
            }
            if (state.showingTrash) refresh()
        }
    }

    fun deleteForever(id: String, password: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.deleteForeverAuthorized(id, password) } }
                .onSuccess {
                    systemUnlockStore.remove(id)
                    onSuccess()
                    state = state.copy(message = tr("已永久删除", "Deleted forever"))
                }
                .onFailure { error ->
                    state = state.copy(message = localizedError(error, "密码错误，未删除备忘录", "Incorrect password. The note was not deleted"))
                }
            refresh()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.emptyTrashUnencrypted()
                    repository.encryptedTrashCount()
                }
            }.onSuccess { encryptedLeft ->
                state = state.copy(
                    message = if (encryptedLeft > 0) {
                        tr(
                            "已删除未加密项目；加密备忘录需逐个输入密码",
                            "Unencrypted items deleted. Enter each encrypted note's password to delete it",
                        )
                    } else {
                        tr("最近删除已清空", "Recently deleted emptied")
                    },
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(message = localizedError(error, "无法清空最近删除", "Could not empty recently deleted"))
            }
            refresh()
        }
    }

    fun addCategory(name: String) = categoryAction { repository.addCategory(name) }
    fun renameCategory(id: String, name: String) = categoryAction { repository.renameCategory(id, name) }
    fun deleteCategory(id: String) = categoryAction {
        repository.deleteCategory(id)
        if (state.selectedCategoryId == id) state = state.copy(selectedCategoryId = null)
    }

    private fun categoryAction(action: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onFailure { state = state.copy(message = localizedError(it, "分类操作失败", "Could not update the group")) }
            refresh()
        }
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun showMessage(chinese: String, english: String) {
        state = state.copy(message = tr(chinese, english))
    }

    fun refreshDriveBackupState() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { DriveBackupEngine(getApplication()).state() } }
                .onSuccess { driveState -> state = state.copy(driveBackup = driveState) }
                .onFailure { error ->
                    state = state.copy(
                        driveBackup = state.driveBackup.copy(lastError = error.message),
                        message = localizedError(error, "无法读取云端备份状态", "Could not read cloud backup status"),
                    )
                }
        }
    }

    fun disableDriveBackupKeepingCloud() {
        if (state.settings.googleDriveBackupEnabled) {
            updateSettings(state.settings.copy(googleDriveBackupEnabled = false))
        }
        resetPendingDriveAuthorization()
        state = state.copy(
            driveBackup = state.driveBackup.copy(backupInProgress = false, lastError = null),
            message = tr(
                "自动备份已关闭；云端内容和登录状态已保留",
                "Automatic backup is off; cloud data and sign-in are preserved",
            ),
        )
    }

    /** Returns the connected account that MainActivity must authorize before deletion. */
    fun disableDriveBackupAndPrepareDeletion(): String? {
        if (state.settings.googleDriveBackupEnabled) {
            updateSettings(state.settings.copy(googleDriveBackupEnabled = false))
        }
        val accountEmail = state.driveBackup.accountEmail
        if (!accountEmail.isNullOrBlank()) return accountEmail

        state = state.copy(driveBackup = state.driveBackup.copy(backupInProgress = true, lastError = null))
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { DriveBackupEngine(getApplication()).clearCloudConfiguration() } }
                .onSuccess { driveState ->
                    state = state.copy(
                        driveBackup = driveState,
                        message = tr(
                            "自动备份已关闭；没有已绑定账号，本地云端配置已清除",
                            "Automatic backup is off; no account was connected, so local cloud settings were cleared",
                        ),
                    )
                }
                .onFailure { error ->
                    state = state.copy(
                        driveBackup = state.driveBackup.copy(backupInProgress = false, lastError = error.message),
                        message = localizedError(
                            error,
                            "自动备份已关闭，但无法清除云端配置",
                            "Automatic backup is off, but cloud settings could not be cleared",
                        ),
                    )
                }
        }
        return null
    }

    fun driveAuthorizationStarted(
        accountEmail: String,
        purpose: DriveAuthorizationPurpose = DriveAuthorizationPurpose.BACKUP,
    ) {
        resetPendingDriveAuthorization()
        pendingDriveAccountEmail = accountEmail
        pendingDriveAuthorizationPurpose = purpose
        driveAuthorizationPending = true
        state = state.copy(
            driveBackup = state.driveBackup.copy(
                backupInProgress = true,
                restoreInProgress = purpose == DriveAuthorizationPurpose.RESTORE,
                lastError = null,
            ),
        )
    }

    fun driveAuthorizationFailed(error: Throwable? = null) {
        val purpose = pendingDriveAuthorizationPurpose
        resetPendingDriveAuthorization()
        state = state.copy(
            driveBackup = state.driveBackup.copy(
                backupInProgress = false,
                restoreInProgress = false,
                lastError = error?.message,
            ),
            message = when {
                purpose == DriveAuthorizationPurpose.DELETE_CLOUD_DATA && error == null -> tr(
                    "已取消删除；自动备份保持关闭，云端内容和登录状态未更改",
                    "Deletion cancelled; automatic backup remains off and cloud data and sign-in were not changed",
                )
                purpose == DriveAuthorizationPurpose.DELETE_CLOUD_DATA -> localizedError(
                    requireNotNull(error),
                    "自动备份已关闭，但无法授权删除云端内容",
                    "Automatic backup is off, but cloud deletion could not be authorized",
                )
                purpose == DriveAuthorizationPurpose.RESTORE && error != null -> driveRestoreError(error)
                purpose == DriveAuthorizationPurpose.RESTORE -> tr(
                    "已取消云端合并",
                    "Cloud merge was cancelled",
                )
                error != null -> localizedError(
                    error,
                    "Google Drive 登录失败",
                    "Google Drive sign-in failed",
                )
                else -> null
            },
        )
    }

    fun completeDriveAuthorization(accessToken: String) {
        if (!driveAuthorizationPending) {
            state = state.copy(
                driveBackup = state.driveBackup.copy(backupInProgress = false),
                message = tr("已忽略过期的 Google 授权结果", "Ignored an expired Google authorization result"),
            )
            return
        }
        val purpose = pendingDriveAuthorizationPurpose
        if (purpose == DriveAuthorizationPurpose.BACKUP && !state.settings.googleDriveBackupEnabled) {
            resetPendingDriveAuthorization()
            state = state.copy(driveBackup = state.driveBackup.copy(backupInProgress = false))
            return
        }
        val accountEmail = pendingDriveAccountEmail ?: state.driveBackup.accountEmail
        if (accountEmail.isNullOrBlank()) {
            driveAuthorizationFailed(IllegalStateException("Google Account email was not returned"))
            return
        }
        state = state.copy(
            driveBackup = state.driveBackup.copy(
                backupInProgress = true,
                restoreInProgress = purpose == DriveAuthorizationPurpose.RESTORE,
                lastError = null,
            ),
        )
        viewModelScope.launch {
            when (purpose) {
                DriveAuthorizationPurpose.DELETE_CLOUD_DATA -> completeDriveCloudDeletion(accessToken, accountEmail)
                DriveAuthorizationPurpose.RESTORE -> completeDriveRestore(accessToken, accountEmail)
                DriveAuthorizationPurpose.BACKUP -> {
                runCatching { DriveBackupEngine(getApplication()).completeAuthorization(accessToken, accountEmail) }
                    .onSuccess { driveState ->
                        resetPendingDriveAuthorization()
                        state = state.copy(
                            driveBackup = driveState,
                            message = when {
                                driveState.restoreRequired && driveState.remoteKeyAvailable -> tr(
                                    "发现此账号的云端备份，请点击“与云端合并”",
                                    "Cloud backup found for this account. Tap Merge from cloud",
                                )
                                driveState.restoreRequired && driveState.remoteBackupAvailable -> tr(
                                    "发现旧版备份；请先让创建它的原设备升级并完成一次备份迁移",
                                    "A legacy backup was found. Update its original device and let it complete one backup migration first",
                                )
                                else -> tr(
                                    "Google Drive 自动备份已启用",
                                    "Google Drive automatic backup is enabled",
                                )
                            },
                        )
                        DriveBackupScheduler.configure(getApplication(), state.settings.googleDriveBackupEnabled)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        resetPendingDriveAuthorization()
                        state = state.copy(
                            driveBackup = DriveBackupEngine(getApplication()).state().copy(
                                backupInProgress = false,
                                lastError = error.message,
                            ),
                            message = localizedError(error, "Google Drive 连接失败", "Could not connect to Google Drive"),
                        )
                    }
                }
            }
        }
    }

    private suspend fun completeDriveRestore(accessToken: String, accountEmail: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val restored = DriveBackupEngine(getApplication()).restoreLatestBackup(
                    accessToken = accessToken,
                    accountEmail = accountEmail,
                )
                systemUnlockStore.cleanupOrphans(repository.encryptedNoteIds())
                restored
            }
        }.onSuccess { (driveState, result) ->
            resetPendingDriveAuthorization()
            state = state.copy(
                selectedCategoryId = null,
                showingTrash = false,
                query = "",
                editor = null,
                driveBackup = driveState.copy(backupInProgress = false),
                loading = true,
                message = tr(
                    "云端合并完成：新增 ${result.importedNoteCount} 篇、更新 ${result.updatedNoteCount} 篇、保留本机 ${result.retainedLocalNoteCount} 篇，共 ${result.noteCount} 篇备忘录",
                    "Cloud merge complete: ${result.importedNoteCount} added, ${result.updatedNoteCount} updated, ${result.retainedLocalNoteCount} kept locally, ${result.noteCount} notes total",
                ),
            )
            refresh()
            DriveBackupScheduler.configure(getApplication(), state.settings.googleDriveBackupEnabled)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            resetPendingDriveAuthorization()
            state = state.copy(
                driveBackup = DriveBackupEngine(getApplication()).state().copy(
                    backupInProgress = false,
                    lastError = error.message,
                ),
                message = driveRestoreError(error),
            )
        }
    }

    private suspend fun completeDriveCloudDeletion(accessToken: String, accountEmail: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val engine = DriveBackupEngine(getApplication())
                val deletedCount = engine.deleteAllCloudData(accessToken)
                Identity.getAuthorizationClient(getApplication<Application>())
                    .revokeAccess(GoogleDriveAuthorization.revokeRequest(getApplication(), accountEmail))
                    .await()
                deletedCount to engine.clearCloudConfiguration()
            }
        }.onSuccess { (deletedCount, driveState) ->
            resetPendingDriveAuthorization()
            state = state.copy(
                driveBackup = driveState,
                message = tr(
                    "已解除 Google Drive 绑定并删除 $deletedCount 个云端文件；本地备忘录已保留",
                    "Google Drive was disconnected and $deletedCount cloud file(s) were deleted; local notes were preserved",
                ),
            )
        }.onFailure { error ->
            resetPendingDriveAuthorization()
            state = state.copy(
                driveBackup = DriveBackupEngine(getApplication()).state().copy(
                    backupInProgress = false,
                    lastError = error.message,
                ),
                message = localizedError(
                    error,
                    "自动备份已关闭，但云端删除或解除绑定未完成；本地备忘录未受影响",
                    "Automatic backup is off, but cloud deletion or disconnect did not finish; local notes were not affected",
                ),
            )
        }
    }

    fun scheduleEncryptedAutoLock() {
        autoLockJob?.cancel()
        val noteId = state.editor?.takeIf { it.encrypted }?.noteId ?: return
        autoLockJob = viewModelScope.launch {
            delay(AUTO_LOCK_DELAY_MS)
            if (state.editor?.noteId == noteId) lockEncryptedEditor()
        }
    }

    fun cancelEncryptedAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = null
    }

    private fun lockEncryptedEditor() {
        val editor = state.editor?.takeIf { it.encrypted } ?: return
        val previousSave = saveJob
        previousSave?.cancel()
        saveJob = null
        state = state.copy(securityOperationInProgress = true)
        viewModelScope.launch {
            previousSave?.join()
            runCatching {
                persistSnapshot(editor, cleanupAttachments = true)
            }.onSuccess {
                if (state.editor?.noteId == editor.noteId) {
                    resetHistory()
                    state = state.copy(
                        editor = null,
                        securityOperationInProgress = false,
                        canUndo = false,
                        canRedo = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = state.copy(
                    securityOperationInProgress = false,
                    message = localizedError(
                        error,
                        "自动锁定前保存失败，备忘录仍保持打开",
                        "Could not save before auto-lock; the note remains open",
                    ),
                )
            }
            refresh()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(850)
            state.editor?.let { saveSnapshot(it) }
            refreshSummariesKeepingEditor()
        }
    }

    private suspend fun saveSnapshot(editor: EditorSession, cleanupAttachments: Boolean = false) {
        runCatching {
            persistSnapshot(editor, cleanupAttachments)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            state = state.copy(message = localizedError(error, "保存失败", "Could not save the note"))
        }
    }

    private suspend fun persistSnapshot(editor: EditorSession, cleanupAttachments: Boolean = false) {
        withContext(Dispatchers.IO) {
            repository.saveDocument(
                noteId = editor.noteId,
                categoryId = editor.categoryId,
                document = editor.document,
                encrypted = editor.encrypted,
                password = editor.password,
                cleanupAttachments = cleanupAttachments,
            )
        }
    }

    private suspend fun refreshSummariesKeepingEditor() {
        val generation = ++refreshGeneration
        val showingTrash = state.showingTrash
        runCatching { withContext(Dispatchers.IO) { repository.listNotes(showingTrash) } }
            .onSuccess { notes ->
                if (generation == refreshGeneration && showingTrash == state.showingTrash) {
                    state = state.copy(notes = notes)
                    DriveBackupScheduler.onLocalDataChanged(getApplication())
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (generation == refreshGeneration) {
                    state = state.copy(message = localizedError(error, "无法刷新备忘录", "Could not refresh notes"))
                }
            }
    }

    private fun recordUndo(document: NoteDocument) {
        if (undoHistory.lastOrNull() != document) {
            undoHistory.addLast(document)
            while (undoHistory.size > MAX_HISTORY) undoHistory.removeFirst()
        }
        redoHistory.clear()
    }

    private fun resetHistory() {
        undoHistory.clear()
        redoHistory.clear()
    }

    private fun tr(chinese: String, english: String): String =
        if (isEnglish(state.settings.language)) english else chinese

    private fun localizedError(error: Throwable, chineseFallback: String, englishFallback: String): String =
        if (isEnglish(state.settings.language)) englishFallback else error.message ?: chineseFallback

    private fun driveRestoreError(error: Throwable): String {
        val messages = generateSequence(error as Throwable?) { it.cause }.mapNotNull(Throwable::message).toList()
        return when {
            messages.any { it.contains("not been migrated", ignoreCase = true) } -> tr(
                "这是旧版密码备份。请先让创建它的原设备升级，并联网完成一次自动或手动备份",
                "This is a legacy password backup. Update its original device and let it complete one online backup first",
            )
            messages.any { it.contains("No Kotj backup", ignoreCase = true) } -> tr(
                "这个 Google 账号中没有找到 Kotj 云端备份",
                "No Kotj cloud backup was found in this Google Account",
            )
            messages.any {
                it.contains("damaged", ignoreCase = true) ||
                    it.contains("does not match", ignoreCase = true) ||
                    it.contains("key record", ignoreCase = true)
            } -> tr(
                "云端备份或账号恢复密钥已损坏，本机内容未被更改",
                "The cloud backup or its account recovery key is damaged; local content was not changed",
            )
            else -> tr(
                "无法与云端合并，请检查网络和 Google 账号",
                "Could not merge from cloud. Check the network and Google Account",
            )
        }
    }

    private fun resetPendingDriveAuthorization() {
        pendingDriveAccountEmail = null
        pendingDriveAuthorizationPurpose = DriveAuthorizationPurpose.BACKUP
        driveAuthorizationPending = false
    }

    private companion object {
        const val AUTO_LOCK_DELAY_MS = 15_000L
        const val MAX_HISTORY = 100
    }
}
