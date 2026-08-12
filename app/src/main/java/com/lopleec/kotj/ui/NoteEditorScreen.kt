@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lopleec.kotj.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lopleec.kotj.data.AttachmentContent
import com.lopleec.kotj.export.ExportFormat
import com.lopleec.kotj.export.NoteExporter
import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.Category
import com.lopleec.kotj.model.EditorSession
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.NoteDocument
import com.lopleec.kotj.model.RichSpan
import com.lopleec.kotj.model.TextKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TITLE_BLOCK_ID = "__note_title__"

@Composable
fun NoteEditorScreen(
    session: EditorSession,
    categories: List<Category>,
    onBack: () -> Unit,
    onUpdate: ((NoteDocument) -> NoteDocument) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onEncrypt: (String) -> Unit,
    useSystemUnlock: Boolean,
    hasSystemUnlock: Boolean,
    onSystemEncrypt: () -> Unit,
    onSystemDelete: () -> Unit,
    onRemoveEncryption: () -> Unit,
    onAddImage: (Uri, String?) -> Unit,
    onReadAttachment: (NoteBlock, String?) -> AttachmentContent,
    onDelete: (String?) -> Unit,
    confirmBeforeDelete: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTogglePinned: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeBlockId by remember(session.noteId) { mutableStateOf<String?>(null) }
    var activeSelections by remember(session.noteId) { mutableStateOf<Map<String, TextRange>>(emptyMap()) }
    var pendingFocusBlockId by remember(session.noteId) { mutableStateOf(if (session.autoFocus) TITLE_BLOCK_ID else null) }
    var moreSheetVisible by remember { mutableStateOf(false) }
    var categorySheetVisible by remember { mutableStateOf(false) }
    var encryptionDialog by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var deletePasswordDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var exportSheetVisible by remember { mutableStateOf(false) }
    var findVisible by remember(session.noteId) { mutableStateOf(session.initialSearchQuery.isNotBlank()) }
    var findQuery by remember(session.noteId) { mutableStateOf(session.initialSearchQuery) }
    var activeFindIndex by remember(session.noteId) { mutableIntStateOf(0) }
    var imageInsertionPending by remember { mutableStateOf<Set<String>?>(null) }
    var titleFieldVisible by remember(session.noteId) {
        mutableStateOf(session.autoFocus || session.document.title.isNotBlank() || session.document.blocks.isEmpty())
    }
    val editorListState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(session.noteId) {
        if (session.document.subtitle.isNotBlank()) {
            onUpdate { document ->
                document.copy(
                    subtitle = "",
                    blocks = listOf(NoteBlock(text = document.subtitle, textKind = TextKind.SUBHEADING)) + document.blocks,
                )
            }
        }
        if (!session.autoFocus) keyboard?.hide()
    }

    LaunchedEffect(session.document.blocks) {
        imageInsertionPending?.let { existingImages ->
            val insertedIndex = session.document.blocks.indexOfFirst {
                it.type == BlockType.IMAGE && it.id !in existingImages
            }
            val target = session.document.blocks.getOrNull(insertedIndex + 1)
            if (insertedIndex >= 0 && target?.type == BlockType.TEXT) {
                pendingFocusBlockId = target.id
                activeBlockId = target.id
                imageInsertionPending = null
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            imageInsertionPending = session.document.blocks.filter { it.type == BlockType.IMAGE }.mapTo(mutableSetOf()) { it.id }
            onAddImage(uri, activeBlockId)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val format = exportFormat
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && format != null && uri != null) {
            val snapshot = session.document
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            when (format) {
                                ExportFormat.DOCX -> NoteExporter.writeDocx(
                                    document = snapshot,
                                    output = output,
                                    resolver = context.contentResolver,
                                    imageReader = { block ->
                                        onReadAttachment(block, session.password)
                                    },
                                )
                                ExportFormat.MARKDOWN -> output.write(
                                    NoteExporter.markdown(snapshot) { block ->
                                        onReadAttachment(block, session.password)
                                    }.toByteArray(),
                                )
                                ExportFormat.TEXT -> output.write(NoteExporter.text(snapshot).toByteArray())
                            }
                        } ?: error(strings("无法创建文件", "Could not create the file"))
                    }
                }.onSuccess {
                    snackbar.showSnackbar(strings("已导出 ${format.extension.uppercase()} 文件", "Exported ${format.extension.uppercase()} file"))
                }.onFailure {
                    snackbar.showSnackbar(it.message ?: strings("导出失败", "Export failed"))
                }
            }
        }
        exportFormat = null
    }

    fun launchExport(format: ExportFormat) {
        exportFormat = format
        val cleanTitle = session.document.title.ifBlank { strings("Kotj备忘录", "Kotj note") }
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .take(60)
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = format.mimeType
                putExtra(Intent.EXTRA_TITLE, "$cleanTitle.${format.extension}")
            },
        )
    }

    fun updateBlock(id: String, transform: (NoteBlock) -> NoteBlock) {
        onUpdate { document ->
            document.copy(blocks = document.blocks.map { if (it.id == id) transform(it) else it })
        }
    }

    fun removeBlock(id: String) {
        onUpdate { document ->
            document.copy(blocks = document.blocks.filterNot { it.id == id })
        }
    }

    fun insertTextBlock(block: NoteBlock) {
        onUpdate { document ->
            val activeIndex = document.blocks.indexOfFirst { it.id == activeBlockId }
            val insertionIndex = when {
                activeBlockId == TITLE_BLOCK_ID -> 0
                activeIndex >= 0 -> activeIndex + 1
                else -> document.blocks.size
            }
            document.copy(blocks = document.blocks.toMutableList().apply { add(insertionIndex, block) })
        }
        activeBlockId = block.id
        activeSelections = emptyMap()
        pendingFocusBlockId = block.id
    }

    fun insertObject(block: NoteBlock) {
        val trailing = NoteBlock(type = BlockType.TEXT, textKind = TextKind.BODY)
        onUpdate { document ->
            val activeIndex = document.blocks.indexOfFirst { it.id == activeBlockId }
            val insertionIndex = when {
                activeBlockId == TITLE_BLOCK_ID -> 0
                activeIndex >= 0 -> activeIndex + 1
                else -> document.blocks.size
            }
            document.copy(blocks = document.blocks.toMutableList().apply {
                add(insertionIndex, block)
                add(insertionIndex + 1, trailing)
            })
        }
        activeBlockId = trailing.id
        activeSelections = emptyMap()
        pendingFocusBlockId = trailing.id
    }

    val findMatches = remember(session.document, findQuery) { findMatches(session.document, findQuery) }
    val activeFindMatch = findMatches.getOrNull(activeFindIndex.coerceIn(0, (findMatches.size - 1).coerceAtLeast(0)))
    LaunchedEffect(findMatches.size, findQuery) { activeFindIndex = 0 }
    LaunchedEffect(activeFindMatch) {
        activeFindMatch?.let { match ->
            val itemIndex = if (match.blockId == TITLE_BLOCK_ID) 0
            else session.document.blocks.indexOfFirst { it.id == match.blockId }.let {
                if (it < 0) 0 else it + if (titleFieldVisible) 1 else 0
            }
            editorListState.animateScrollToItem(itemIndex)
        }
    }

    fun applyTextFormat(kind: FormatKind, color: Color? = null) {
        val selections = activeSelections.filterValues { !it.collapsed }
        if (selections.isEmpty()) {
            scope.launch { snackbar.showSnackbar(strings("请先选择需要设置格式的文字", "Select text to format first")) }
            return
        }
        onUpdate { document ->
            document.copy(
                blocks = document.blocks.map { block ->
                    selections[block.id]?.let { selection -> applyFormat(block, selection, kind, color) } ?: block
                },
            )
        }
    }

    fun changeActiveTextKind(kind: TextKind) {
        val id = activeBlockId ?: return
        if (id == TITLE_BLOCK_ID) {
            if (kind == TextKind.TITLE) return
            val block = NoteBlock(type = BlockType.TEXT, text = session.document.title, textKind = kind)
            onUpdate { document -> document.copy(title = "", blocks = listOf(block) + document.blocks) }
            titleFieldVisible = false
            activeBlockId = block.id
            pendingFocusBlockId = block.id
            return
        }
        if (kind == TextKind.TITLE) {
            val current = session.document.blocks.firstOrNull { it.id == id && it.type == BlockType.TEXT } ?: return
            onUpdate { document ->
                val oldTitleAsBody = document.title.takeIf { it.isNotBlank() }?.let {
                    NoteBlock(type = BlockType.TEXT, text = it, textKind = TextKind.BODY)
                }
                val remaining = document.blocks.filterNot { it.id == id }.toMutableList()
                oldTitleAsBody?.let { remaining.add(0, it) }
                document.copy(title = current.text, blocks = remaining)
            }
            activeBlockId = TITLE_BLOCK_ID
            titleFieldVisible = true
            pendingFocusBlockId = TITLE_BLOCK_ID
        } else {
            updateBlock(id) { it.copy(textKind = kind) }
            pendingFocusBlockId = id
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            session.document.title.ifBlank { strings("无标题", "Untitled") },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, strings("返回", "Back"))
                        }
                    },
                    actions = {
                        IconButton(onClick = onUndo, enabled = canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, strings("撤销", "Undo"))
                        }
                        IconButton(onClick = onRedo, enabled = canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, strings("重做", "Redo"))
                        }
                        IconButton(onClick = { moreSheetVisible = true }) {
                            Icon(Icons.Outlined.MoreVert, strings("更多", "More"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (findVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(strings("在备忘录中查找", "Find in note")) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            supportingText = if (findQuery.isNotBlank()) ({
                                Text(if (findMatches.isEmpty()) strings("无结果", "No results") else "${activeFindIndex + 1}/${findMatches.size}")
                            }) else null,
                            singleLine = true,
                        )
                        IconButton(
                            onClick = { if (findMatches.isNotEmpty()) activeFindIndex = (activeFindIndex - 1 + findMatches.size) % findMatches.size },
                            enabled = findMatches.isNotEmpty(),
                        ) { Icon(Icons.Outlined.KeyboardArrowUp, strings("上一个", "Previous")) }
                        IconButton(
                            onClick = { if (findMatches.isNotEmpty()) activeFindIndex = (activeFindIndex + 1) % findMatches.size },
                            enabled = findMatches.isNotEmpty(),
                        ) { Icon(Icons.Outlined.KeyboardArrowDown, strings("下一个", "Next")) }
                        IconButton(onClick = { findVisible = false; findQuery = "" }) {
                            Icon(Icons.Outlined.Close, strings("关闭查找", "Close find"))
                        }
                    }
                }
            }
        },
        bottomBar = {
            EditorToolbar(
                activeKind = if (activeBlockId == TITLE_BLOCK_ID) TextKind.TITLE
                else session.document.blocks.firstOrNull { it.id == activeBlockId }?.textKind,
                onTextKind = ::changeActiveTextKind,
                onFormat = ::applyTextFormat,
                onAddImage = {
                    imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onAddTable = {
                    insertObject(NoteBlock(type = BlockType.TABLE, tableCells = List(2) { List(2) { "" } }))
                },
                onAddDivider = { insertObject(NoteBlock(type = BlockType.DIVIDER)) },
                onAddNumberedList = {
                    insertTextBlock(NoteBlock(text = "1. ", textKind = TextKind.BODY))
                },
                onAddBulletList = {
                    insertTextBlock(NoteBlock(text = "• ", textKind = TextKind.BODY))
                },
                onAddChecklist = {
                    insertTextBlock(NoteBlock(textKind = TextKind.CHECKLIST))
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = editorListState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (titleFieldVisible) {
                item(key = TITLE_BLOCK_ID) {
                    EditorTitleField(
                        title = session.document.title,
                        requestFocus = pendingFocusBlockId == TITLE_BLOCK_ID,
                        searchQuery = findQuery,
                        activeMatch = activeFindMatch?.takeIf { it.blockId == TITLE_BLOCK_ID },
                        onFocusConsumed = { pendingFocusBlockId = null },
                        onFocused = { activeBlockId = TITLE_BLOCK_ID; activeSelections = emptyMap() },
                        onTitleChange = { title -> onUpdate { it.copy(title = title) } },
                        onEnter = { title, body ->
                            var targetId: String? = null
                            onUpdate { document ->
                                val result = applyTitleEnter(document, title, body)
                                targetId = result.second
                                result.first
                            }
                            targetId?.let { id ->
                                activeBlockId = id
                                pendingFocusBlockId = id
                            }
                        },
                    )
                }
            }
            itemsIndexed(session.document.blocks, key = { _, block -> block.id }) { index, block ->
                val previousObject = session.document.blocks.getOrNull(index - 1)?.takeIf { it.type != BlockType.TEXT }
                when (block.type) {
                    BlockType.TEXT -> if (block.textKind == TextKind.CHECKLIST) {
                        InlineChecklistEditor(
                            block = block,
                            requestFocus = pendingFocusBlockId == block.id,
                            searchQuery = findQuery,
                            activeMatch = activeFindMatch?.takeIf { it.blockId == block.id },
                            onFocusRequestConsumed = { pendingFocusBlockId = null },
                            onFocused = { selection ->
                                activeBlockId = block.id
                                activeSelections = mapOf(block.id to selection)
                            },
                            onChange = { changed -> updateBlock(block.id) { changed } },
                            onExitEmpty = {
                                updateBlock(block.id) { it.copy(text = "", textKind = TextKind.BODY, checked = false) }
                                activeBlockId = block.id
                                pendingFocusBlockId = block.id
                            },
                            onRemoveMarker = { text ->
                                updateBlock(block.id) { it.copy(text = text, textKind = TextKind.BODY, checked = false) }
                                activeBlockId = block.id
                                pendingFocusBlockId = block.id
                            },
                            onContinue = { textBefore, textAfter ->
                                val next = NoteBlock(type = BlockType.TEXT, text = textAfter, textKind = TextKind.CHECKLIST)
                                onUpdate { document -> document.copy(blocks = document.blocks.flatMap {
                                    if (it.id == block.id) listOf(it.copy(text = textBefore), next) else listOf(it)
                                }) }
                                activeBlockId = next.id
                                pendingFocusBlockId = next.id
                            },
                        )
                    } else {
                        InlineTextBlockEditor(
                            block = block,
                            requestFocus = pendingFocusBlockId == block.id,
                            searchQuery = findQuery,
                            activeMatch = activeFindMatch?.takeIf { it.blockId == block.id },
                            hasPreviousObject = previousObject != null,
                            onFocusRequestConsumed = { pendingFocusBlockId = null },
                            onFocused = { selection ->
                                activeBlockId = block.id
                                activeSelections = mapOf(block.id to selection)
                            },
                            onChange = { changed -> updateBlock(block.id) { changed } },
                            onDeletePreviousObject = { previousObject?.let { removeBlock(it.id) } },
                        )
                    }
                    BlockType.IMAGE -> ImageBlockEditor(
                        block = block,
                        password = session.password,
                        onReadAttachment = onReadAttachment,
                    )
                    BlockType.TABLE -> TableBlockEditor(
                        block = block,
                        onChange = { changed -> updateBlock(block.id) { changed } },
                        searchQuery = findQuery,
                        activeMatch = activeFindMatch?.takeIf { it.blockId == block.id },
                        onFocused = { activeBlockId = block.id; activeSelections = emptyMap() },
                    )
                    BlockType.DIVIDER -> DividerBlock()
                }
            }
        }
    }

    if (categorySheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { categorySheetVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("移动到分类", "Move to group"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text(strings("未分类", "No group")) },
                leadingContent = { RadioButton(selected = session.categoryId == null, onClick = null) },
                modifier = Modifier.clickable {
                    onCategoryChange(null)
                    categorySheetVisible = false
                },
            )
            categories.forEach { category ->
                ListItem(
                    headlineContent = { Text(category.localizedName(strings)) },
                    leadingContent = { RadioButton(selected = session.categoryId == category.id, onClick = null) },
                    modifier = Modifier.clickable {
                        onCategoryChange(category.id)
                        categorySheetVisible = false
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
    if (moreSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { moreSheetVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("备忘录操作", "Note actions"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text(strings("在备忘录中查找", "Find in note")) },
                leadingContent = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.clickable { moreSheetVisible = false; findVisible = true },
            )
            ListItem(
                headlineContent = { Text(if (session.pinned) strings("取消置顶", "Unpin") else strings("置顶", "Pin")) },
                leadingContent = { Icon(Icons.Outlined.PushPin, null) },
                modifier = Modifier.clickable { moreSheetVisible = false; onTogglePinned() },
            )
            ListItem(
                headlineContent = { Text(if (session.encrypted) strings("移除加密", "Remove encryption") else strings("加密备忘录", "Encrypt note")) },
                leadingContent = { Icon(if (session.encrypted) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null) },
                modifier = Modifier.clickable {
                    moreSheetVisible = false
                    when {
                        session.encrypted -> onRemoveEncryption()
                        useSystemUnlock -> onSystemEncrypt()
                        else -> encryptionDialog = true
                    }
                },
            )
            ListItem(
                headlineContent = { Text(strings("移动到分组", "Move to group")) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                modifier = Modifier.clickable { moreSheetVisible = false; categorySheetVisible = true },
            )
            ListItem(
                headlineContent = { Text(strings("导出", "Export")) },
                leadingContent = { Icon(Icons.Outlined.SaveAlt, null) },
                modifier = Modifier.clickable { moreSheetVisible = false; exportSheetVisible = true },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(strings("移到最近删除", "Move to recently deleted"), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                    moreSheetVisible = false
                    when {
                        session.encrypted && hasSystemUnlock -> onSystemDelete()
                        session.encrypted -> deletePasswordDialog = true
                        confirmBeforeDelete -> deleteConfirm = true
                        else -> onDelete(null)
                    }
                },
            )
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
    if (exportSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { exportSheetVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("导出", "Export"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            listOf(
                Triple(strings("Word 文档", "Word document"), ".docx", ExportFormat.DOCX),
                Triple("Markdown", ".md", ExportFormat.MARKDOWN),
                Triple(strings("纯文本", "Plain text"), ".txt", ExportFormat.TEXT),
            ).forEach { (label, extension, format) ->
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(extension) },
                    leadingContent = { Icon(Icons.Outlined.SaveAlt, null) },
                    modifier = Modifier.clickable { exportSheetVisible = false; launchExport(format) },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
    if (encryptionDialog) {
        PasswordDialog(
            title = strings("加密这篇备忘录", "Encrypt this note"),
            body = strings("标题、正文、搜索索引和插入的图片都会加密。密码无法找回，请妥善保存。", "The title, note text, search index, and inserted photos will be encrypted. The password cannot be recovered."),
            confirmLabel = strings("加密", "Encrypt"),
            onDismiss = { encryptionDialog = false },
            onConfirm = {
                onEncrypt(it)
                encryptionDialog = false
            },
            requireConfirmation = true,
        )
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(strings("移到最近删除？", "Move to recently deleted?")) },
            text = { Text(strings("可以在设置的保留期内恢复。", "It can be restored during the configured retention period.")) },
            confirmButton = { TextButton(onClick = { deleteConfirm = false; onDelete(null) }) { Text(strings("删除", "Delete")) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(strings("取消", "Cancel")) } },
        )
    }
    if (deletePasswordDialog) {
        PasswordDialog(
            title = strings("删除加密备忘录", "Delete encrypted note"),
            body = strings(
                "输入这篇备忘录的密码后，才能移到最近删除。",
                "Enter this note's password before moving it to recently deleted.",
            ),
            confirmLabel = strings("删除", "Delete"),
            onDismiss = { deletePasswordDialog = false },
            onConfirm = onDelete,
        )
    }
}

private enum class FormatKind { BOLD, ITALIC, UNDERLINE, STRIKE, COLOR }

@Composable
private fun EditorToolbar(
    activeKind: TextKind?,
    onTextKind: (TextKind) -> Unit,
    onFormat: (FormatKind, Color?) -> Unit,
    onAddImage: () -> Unit,
    onAddTable: () -> Unit,
    onAddDivider: () -> Unit,
    onAddNumberedList: () -> Unit,
    onAddBulletList: () -> Unit,
    onAddChecklist: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var styleSheet by remember { mutableStateOf(false) }
    var insertSheet by remember { mutableStateOf(false) }
    var colorSheet by remember { mutableStateOf(false) }
    val colors = listOf(
        Color(0xFFB3261E), Color(0xFF00639B), Color(0xFF2E7D32),
        Color(0xFF7B1FA2), Color(0xFFEF6C00),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { styleSheet = true }) { Icon(Icons.Outlined.FormatSize, strings("文本样式", "Text style")) }
            IconButton(onClick = { onFormat(FormatKind.BOLD, null) }) { Icon(Icons.Outlined.FormatBold, strings("加粗", "Bold")) }
            IconButton(onClick = { onFormat(FormatKind.ITALIC, null) }) { Icon(Icons.Outlined.FormatItalic, strings("斜体", "Italic")) }
            IconButton(onClick = { onFormat(FormatKind.UNDERLINE, null) }) { Icon(Icons.Outlined.FormatUnderlined, strings("下划线", "Underline")) }
            IconButton(onClick = { onFormat(FormatKind.STRIKE, null) }) { Icon(Icons.Outlined.FormatStrikethrough, strings("删除线", "Strikethrough")) }
            IconButton(onClick = { colorSheet = true }) { Icon(Icons.Outlined.FormatColorText, strings("文字颜色", "Text color")) }
            FilledTonalIconButton(onClick = { insertSheet = true }) { Icon(Icons.Outlined.Add, strings("插入", "Insert")) }
        }
    }

    if (styleSheet) {
        ModalBottomSheet(
            onDismissRequest = { styleSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("文本样式", "Text style"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            TextKind.entries.forEach { kind ->
                ListItem(
                    headlineContent = { Text(kind.label(strings)) },
                    supportingContent = {
                        Text(
                            when (kind) {
                                TextKind.TITLE -> strings("作为整篇备忘录标题", "Use as the note title")
                                TextKind.BODY -> strings("普通正文", "Regular note text")
                                TextKind.HEADING -> strings("正文中的小标题", "Heading inside the note")
                                TextKind.SUBHEADING -> strings("次级标题或副标题", "Subheading")
                                TextKind.QUOTE -> strings("引用文字", "Quoted text")
                                TextKind.CHECKLIST -> strings("待办事项", "Checklist item")
                            },
                        )
                    },
                    leadingContent = { RadioButton(selected = kind == activeKind, onClick = null) },
                    modifier = Modifier.clickable {
                        onTextKind(kind)
                        styleSheet = false
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    if (insertSheet) {
        ModalBottomSheet(
            onDismissRequest = { insertSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("插入内容", "Insert"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text(strings("编号列表", "Numbered list")) },
                supportingContent = { Text("1.  2.  3.") },
                leadingContent = { Icon(Icons.Outlined.FormatListNumbered, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddNumberedList() },
            )
            ListItem(
                headlineContent = { Text(strings("圆点列表", "Bulleted list")) },
                supportingContent = { Text("•  •  •") },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddBulletList() },
            )
            ListItem(
                headlineContent = { Text(strings("待办清单", "Checklist")) },
                supportingContent = { Text(strings("使用可勾选的复选框", "Uses checkable boxes")) },
                leadingContent = { Icon(Icons.Outlined.CheckBox, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddChecklist() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(strings("照片", "Photo")) },
                leadingContent = { Icon(Icons.Outlined.Image, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddImage() },
            )
            ListItem(
                headlineContent = { Text(strings("表格", "Table")) },
                leadingContent = { Icon(Icons.Outlined.TableChart, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddTable() },
            )
            ListItem(
                headlineContent = { Text(strings("分界线", "Divider")) },
                leadingContent = { Icon(Icons.Outlined.HorizontalRule, null) },
                modifier = Modifier.clickable { insertSheet = false; onAddDivider() },
            )
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    if (colorSheet) {
        ModalBottomSheet(
            onDismissRequest = { colorSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                strings("文字颜色", "Text color"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onFormat(FormatKind.COLOR, null)
                    colorSheet = false
                }) {
                    Text(strings("默认", "Default"))
                }
                colors.forEach { color ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(color).clickable {
                            onFormat(FormatKind.COLOR, color)
                            colorSheet = false
                        },
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

private fun TextKind.label(strings: AppStrings): String = when (this) {
        TextKind.TITLE -> strings("标题", "Title")
        TextKind.BODY -> strings("正文", "Body")
        TextKind.HEADING -> strings("小标题", "Heading")
        TextKind.SUBHEADING -> strings("副标题", "Subheading")
        TextKind.QUOTE -> strings("引用", "Quote")
        TextKind.CHECKLIST -> strings("待办清单", "Checklist")
    }

private data class FindMatch(val blockId: String, val start: Int, val end: Int, val cellIndex: Int? = null)

private const val OBJECT_DELETE_MARKER = "\u2060"

internal fun normalizeEditorFlow(document: NoteDocument): NoteDocument {
    val normalized = buildList {
        document.blocks.forEachIndexed { index, block ->
            add(if (block.type == BlockType.TEXT && block.textKind == TextKind.TITLE) block.copy(textKind = TextKind.BODY) else block)
            if (block.type != BlockType.TEXT) {
                val next = document.blocks.getOrNull(index + 1)
                if (next?.type != BlockType.TEXT || next.textKind == TextKind.CHECKLIST) {
                    add(NoteBlock(type = BlockType.TEXT, textKind = TextKind.BODY))
                }
            }
        }
        if (isEmpty()) add(NoteBlock(type = BlockType.TEXT, textKind = TextKind.BODY))
    }
    return if (normalized == document.blocks) document else document.copy(blocks = normalized)
}

private fun applyTitleEnter(
    document: NoteDocument,
    title: String,
    body: String,
): Pair<NoteDocument, String> {
    val reusable = document.blocks.firstOrNull()?.takeIf {
        it.type == BlockType.TEXT && it.textKind != TextKind.CHECKLIST && it.text.isEmpty()
    }
    val target = reusable?.copy(text = body, textKind = TextKind.BODY)
        ?: NoteBlock(type = BlockType.TEXT, text = body, textKind = TextKind.BODY)
    val blocks = if (reusable == null) {
        listOf(target) + document.blocks
    } else {
        document.blocks.map { if (it.id == reusable.id) target else it }
    }
    return document.copy(title = title, blocks = blocks) to target.id
}

private fun findMatches(document: NoteDocument, query: String): List<FindMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return buildList {
        fun addMatches(id: String, value: String, cellIndex: Int? = null) {
            var from = 0
            while (from < value.length) {
                val start = value.indexOf(needle, from, ignoreCase = true)
                if (start < 0) break
                add(FindMatch(id, start, start + needle.length, cellIndex))
                from = start + needle.length
            }
        }
        addMatches(TITLE_BLOCK_ID, document.title)
        document.blocks.forEach { block ->
            when (block.type) {
                BlockType.TEXT -> addMatches(block.id, block.text)
                BlockType.TABLE -> block.tableCells.flatten().forEachIndexed { index, cell -> addMatches(block.id, cell, index) }
                BlockType.IMAGE, BlockType.DIVIDER -> Unit
            }
        }
    }
}

@Composable
private fun EditorTitleField(
    title: String,
    requestFocus: Boolean,
    searchQuery: String,
    activeMatch: FindMatch?,
    onFocusConsumed: () -> Unit,
    onFocused: () -> Unit,
    onTitleChange: (String) -> Unit,
    onEnter: (String, String) -> Unit,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    LaunchedEffect(title) {
        if (value.text != title) value = value.copy(text = title, selection = value.selection.constrain(0, title.length))
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            requester.requestFocus()
            keyboard?.show()
            onFocusConsumed()
        }
    }
    val transformation = searchTransformation(
        query = searchQuery,
        activeMatch = activeMatch,
        offset = 0,
        normalColor = MaterialTheme.colorScheme.tertiaryContainer,
        activeColor = MaterialTheme.colorScheme.primaryContainer,
    )
    BasicTextField(
        value = value,
        onValueChange = { incoming ->
            val newline = incoming.text.indexOf('\n')
            if (newline >= 0) {
                val nextTitle = incoming.text.substring(0, newline)
                val body = incoming.text.substring(newline + 1)
                value = TextFieldValue(nextTitle, TextRange(nextTitle.length))
                onEnter(nextTitle, body)
            } else {
                value = incoming
                onTitleChange(incoming.text)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .padding(top = 8.dp, bottom = 2.dp),
        textStyle = TextKind.TITLE.editorBaseTextStyle().copy(
            color = MaterialTheme.colorScheme.onSurface,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = editorLineHeightStyle,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
    )
}

@Composable
private fun InlineTextBlockEditor(
    block: NoteBlock,
    requestFocus: Boolean,
    searchQuery: String,
    activeMatch: FindMatch?,
    hasPreviousObject: Boolean,
    onFocusRequestConsumed: () -> Unit,
    onFocused: (TextRange) -> Unit,
    onChange: (NoteBlock) -> Unit,
    onDeletePreviousObject: () -> Unit,
) {
    val marker = if (hasPreviousObject) OBJECT_DELETE_MARKER else ""
    val requester = remember(block.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(block.id) {
        mutableStateOf(TextFieldValue(marker + block.text, TextRange(marker.length + block.text.length)))
    }
    LaunchedEffect(block.text, marker) {
        val expected = marker + block.text
        if (value.text != expected) {
            value = TextFieldValue(expected, value.selection.constrain(marker.length, expected.length))
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            value = value.copy(selection = TextRange(value.text.length))
            requester.requestFocus()
            keyboard?.show()
            onFocusRequestConsumed()
        }
    }
    val transformation = searchTransformation(
        query = searchQuery,
        activeMatch = activeMatch,
        offset = marker.length,
        normalColor = MaterialTheme.colorScheme.tertiaryContainer,
        activeColor = MaterialTheme.colorScheme.primaryContainer,
        richSpans = block.spans,
    )
    BasicTextField(
        value = value,
        onValueChange = { incoming ->
            if (hasPreviousObject && !incoming.text.startsWith(OBJECT_DELETE_MARKER)) {
                onDeletePreviousObject()
                val clean = incoming.text.removePrefix(OBJECT_DELETE_MARKER)
                value = TextFieldValue(clean, incoming.selection.constrain(0, clean.length))
                onChange(block.copy(text = clean, spans = adjustSpans(block.text, clean, block.spans)))
                return@BasicTextField
            }
            val previousPlain = value.withoutLeadingMarker(marker)
            val incomingPlain = incoming.withoutLeadingMarker(marker)
            val transformed = transformUnifiedInput(previousPlain, incomingPlain)
            val displayText = marker + transformed.text
            value = TextFieldValue(
                displayText,
                TextRange(transformed.selection.start + marker.length, transformed.selection.end + marker.length),
            )
            val changed = block.copy(
                text = transformed.text,
                spans = adjustSpans(block.text, transformed.text, block.spans),
            )
            onFocused(transformed.selection)
            onChange(changed)
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) onFocused(value.selection.shiftLeft(marker.length))
            }
            .onPreviewKeyEvent { event ->
                if (
                    hasPreviousObject && event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                    value.selection.collapsed && value.selection.start <= marker.length
                ) {
                    onDeletePreviousObject()
                    true
                } else false
            }
            .padding(vertical = 3.dp),
        textStyle = block.textKind.editorBaseTextStyle().copy(
            color = MaterialTheme.colorScheme.onSurface,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = editorLineHeightStyle,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
    )
}

@Composable
private fun InlineChecklistEditor(
    block: NoteBlock,
    requestFocus: Boolean,
    searchQuery: String,
    activeMatch: FindMatch?,
    onFocusRequestConsumed: () -> Unit,
    onFocused: (TextRange) -> Unit,
    onChange: (NoteBlock) -> Unit,
    onExitEmpty: () -> Unit,
    onRemoveMarker: (String) -> Unit,
    onContinue: (String, String) -> Unit,
) {
    val marker = OBJECT_DELETE_MARKER
    val requester = remember(block.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(block.id) {
        mutableStateOf(TextFieldValue(marker + block.text, TextRange(marker.length + block.text.length)))
    }
    LaunchedEffect(block.text) {
        val expected = marker + block.text
        if (value.text != expected) {
            value = TextFieldValue(expected, value.selection.constrain(marker.length, expected.length))
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            requester.requestFocus()
            keyboard?.show()
            onFocusRequestConsumed()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked = block.checked, onCheckedChange = { onChange(block.copy(checked = it)) })
        BasicTextField(
            value = value,
            onValueChange = { incoming ->
                if (!incoming.text.startsWith(marker)) {
                    onRemoveMarker(incoming.text)
                    return@BasicTextField
                }
                val plain = incoming.withoutLeadingMarker(marker)
                val newline = plain.text.indexOf('\n')
                if (newline >= 0) {
                    if (block.text.isBlank()) onExitEmpty()
                    else onContinue(plain.text.substring(0, newline), plain.text.substring(newline + 1))
                } else {
                    value = incoming
                    onFocused(plain.selection)
                    onChange(block.copy(text = plain.text, spans = adjustSpans(block.text, plain.text, block.spans)))
                }
            },
            modifier = Modifier.weight(1f).focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused(value.selection.shiftLeft(marker.length)) }
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                        value.selection.collapsed && value.selection.start <= marker.length
                    ) {
                        onRemoveMarker(block.text)
                        true
                    } else false
                }
                .padding(top = 12.dp, bottom = 8.dp),
            textStyle = TextKind.BODY.editorBaseTextStyle().copy(
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (block.checked) TextDecoration.LineThrough else null,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = searchTransformation(
                query = searchQuery,
                activeMatch = activeMatch,
                offset = marker.length,
                normalColor = MaterialTheme.colorScheme.tertiaryContainer,
                activeColor = MaterialTheme.colorScheme.primaryContainer,
                richSpans = block.spans,
            ),
        )
    }
}

private fun searchTransformation(
    query: String,
    activeMatch: FindMatch?,
    offset: Int,
    normalColor: Color,
    activeColor: Color,
    richSpans: List<RichSpan> = emptyList(),
): VisualTransformation = VisualTransformation { source ->
    val result = AnnotatedString.Builder(source)
    richSpans.forEach { span ->
        val start = (span.start + offset).coerceIn(0, source.length)
        val end = (span.end + offset).coerceIn(start, source.length)
        if (start < end) result.addStyle(span.asComposeStyle(), start, end)
    }
    val needle = query.trim()
    if (needle.isNotEmpty()) {
        var from = offset
        while (from < source.length) {
            val start = source.text.indexOf(needle, from, ignoreCase = true)
            if (start < 0) break
            result.addStyle(SpanStyle(background = normalColor), start, start + needle.length)
            from = start + needle.length
        }
        activeMatch?.let {
            val start = (it.start + offset).coerceIn(0, source.length)
            val end = (it.end + offset).coerceIn(start, source.length)
            if (start < end) result.addStyle(SpanStyle(background = activeColor), start, end)
        }
    }
    TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
}

private fun TextFieldValue.withoutLeadingMarker(marker: String): TextFieldValue {
    if (marker.isEmpty() || !text.startsWith(marker)) return this
    return TextFieldValue(
        text = text.removePrefix(marker),
        selection = selection.shiftLeft(marker.length),
        composition = composition?.shiftLeft(marker.length),
    )
}

private fun TextRange.shiftLeft(offset: Int): TextRange = TextRange(
    (start - offset).coerceAtLeast(0),
    (end - offset).coerceAtLeast(0),
)

private val editorLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun TextKind.editorLineHeight() = when (this) {
    TextKind.TITLE -> 36.sp
    TextKind.HEADING -> 32.sp
    TextKind.SUBHEADING -> 26.sp
    TextKind.QUOTE, TextKind.CHECKLIST, TextKind.BODY -> 24.sp
}

private fun TextKind.editorBaseTextStyle(): TextStyle = TextStyle(
    fontSize = when (this) {
        TextKind.TITLE -> 28.sp
        TextKind.HEADING -> 24.sp
        TextKind.SUBHEADING -> 19.sp
        TextKind.QUOTE, TextKind.CHECKLIST, TextKind.BODY -> 17.sp
    },
    fontWeight = when (this) {
        TextKind.TITLE, TextKind.HEADING -> FontWeight.Bold
        TextKind.SUBHEADING -> FontWeight.SemiBold
        TextKind.QUOTE, TextKind.CHECKLIST, TextKind.BODY -> FontWeight.Normal
    },
    fontStyle = if (this == TextKind.QUOTE) FontStyle.Italic else FontStyle.Normal,
    lineHeight = editorLineHeight(),
)

private fun RichSpan.asComposeStyle(): SpanStyle {
    val decorations = buildList {
        if (underline) add(TextDecoration.Underline)
        if (strikeThrough) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = colorArgb?.let { Color(it.toInt()) } ?: Color.Unspecified,
    )
}

private fun transformUnifiedInput(previous: TextFieldValue, incoming: TextFieldValue): TextFieldValue {
    if (incoming.text.length != previous.text.length + 1) return incoming
    val insertion = incoming.selection.start - 1
    if (insertion !in incoming.text.indices) return incoming
    if (incoming.text[insertion] == ' ') {
        val lineStart = incoming.text.lastIndexOf('\n', insertion - 1).let { if (it < 0) 0 else it + 1 }
        if (incoming.text.substring(lineStart, insertion + 1) == "- ") {
            return TextFieldValue(
                text = incoming.text.replaceRange(lineStart, lineStart + 1, "•"),
                selection = incoming.selection,
                composition = null,
            )
        }
    }
    if (incoming.text[insertion] != '\n') return incoming
    val before = previous.text.substring(0, insertion.coerceAtMost(previous.text.length))
    val currentLine = before.substringAfterLast('\n')
    val numbered = Regex("^(\\s*)(\\d+)([.\uff0e])\\s*(.*)$").matchEntire(currentLine)
    val bullet = Regex("^(\\s*)[•-]\\s*(.*)$").matchEntire(currentLine)
    if (
        (numbered != null && numbered.groupValues[4].isBlank()) ||
        (bullet != null && bullet.groupValues[2].isBlank())
    ) {
        val lineStart = before.length - currentLine.length
        val text = incoming.text.replaceRange(lineStart, insertion + 1, "")
        return TextFieldValue(text, TextRange(lineStart), composition = null)
    }
    val numberedContinuation = numbered?.let { match ->
        val number = match.groupValues[2].toLongOrNull()?.plus(1) ?: return@let ""
        "${match.groupValues[1]}$number${match.groupValues[3]} "
    }.orEmpty()
    val bulletContinuation = bullet?.let { match ->
        "${match.groupValues[1]}• "
    }.orEmpty()
    val continuation = numberedContinuation.ifEmpty { bulletContinuation }
    val replacement = "\n$continuation"
    val text = incoming.text.replaceRange(insertion, insertion + 1, replacement)
    val cursor = insertion + replacement.length
    return TextFieldValue(
        text = text,
        selection = TextRange(cursor),
        composition = null,
    )
}

@Composable
private fun ImageBlockEditor(
    block: NoteBlock,
    password: String?,
    onReadAttachment: (NoteBlock, String?) -> AttachmentContent,
) {
    PersistedImage(
        block = block,
        password = password,
        onReadAttachment = onReadAttachment,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PersistedImage(
    block: NoteBlock,
    password: String?,
    onReadAttachment: (NoteBlock, String?) -> AttachmentContent,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, block.imageUri, block.imageEncrypted, password) {
        value = if (block.imageUri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bytes = onReadAttachment(block, password).bytes
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) sampleSize *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()
        }
    }
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Image, strings("图片不可用", "Photo unavailable"), modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        ComposeImage(
            bitmap = bitmap!!,
            contentDescription = strings("笔记图片", "Note photo"),
            modifier = modifier.aspectRatio(bitmap!!.width.toFloat() / bitmap!!.height.coerceAtLeast(1)),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

@Composable
private fun TableBlockEditor(
    block: NoteBlock,
    onChange: (NoteBlock) -> Unit,
    searchQuery: String,
    activeMatch: FindMatch?,
    onFocused: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val rows = block.tableCells.ifEmpty { List(2) { List(2) { "" } } }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { columnIndex, cell ->
                        OutlinedTextField(
                            value = cell,
                            onValueChange = { value ->
                                val next = rows.mapIndexed { r, oldRow ->
                                    oldRow.mapIndexed { c, old -> if (r == rowIndex && c == columnIndex) value else old }
                                }
                                onChange(block.copy(tableCells = next))
                            },
                            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocused() },
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = false,
                            minLines = 1,
                            visualTransformation = searchTransformation(
                                query = searchQuery,
                                activeMatch = activeMatch?.takeIf {
                                    it.cellIndex == rowIndex * (rows.firstOrNull()?.size ?: 1) + columnIndex
                                },
                                offset = 0,
                                normalColor = MaterialTheme.colorScheme.tertiaryContainer,
                                activeColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    val columns = rows.firstOrNull()?.size?.coerceAtLeast(1) ?: 2
                    onChange(block.copy(tableCells = rows + listOf(List(columns) { "" })))
                },
            ) { Text(strings("+ 行", "+ Row")) }
            TextButton(
                onClick = { onChange(block.copy(tableCells = rows.map { it + "" })) },
            ) { Text(strings("+ 列", "+ Column")) }
            if (rows.size > 1) {
                TextButton(onClick = { onChange(block.copy(tableCells = rows.dropLast(1))) }) { Text(strings("− 行", "− Row")) }
            }
            if ((rows.firstOrNull()?.size ?: 0) > 1) {
                TextButton(onClick = { onChange(block.copy(tableCells = rows.map { it.dropLast(1) })) }) { Text(strings("− 列", "− Column")) }
            }
        }
    }
}

@Composable
private fun DividerBlock() {
    HorizontalDivider(Modifier.fillMaxWidth().padding(vertical = 12.dp))
}

private fun adjustSpans(oldText: String, newText: String, spans: List<RichSpan>): List<RichSpan> {
    if (oldText == newText || spans.isEmpty()) return spans
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (
        suffix < oldText.length - prefix &&
        suffix < newText.length - prefix &&
        oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]
    ) suffix++
    val oldEnd = oldText.length - suffix
    val newEnd = newText.length - suffix
    val delta = newEnd - oldEnd

    return spans.mapNotNull { span ->
        val adjusted = when {
            span.end <= prefix -> span
            span.start >= oldEnd -> span.copy(start = span.start + delta, end = span.end + delta)
            span.start < prefix && span.end > oldEnd -> span.copy(end = span.end + delta)
            span.start < prefix -> span.copy(end = prefix)
            span.end > oldEnd -> span.copy(start = newEnd, end = span.end + delta)
            else -> null
        }
        adjusted?.takeIf { it.start < it.end }
    }
}

private fun applyFormat(block: NoteBlock, selection: TextRange, kind: FormatKind, color: Color?): NoteBlock {
    val start = selection.min.coerceIn(0, block.text.length)
    val end = selection.max.coerceIn(start, block.text.length)
    if (start == end) return block
    if (kind == FormatKind.COLOR) {
        val cleared = block.spans.flatMap { span ->
            if (span.end <= start || span.start >= end || span.colorArgb == null) return@flatMap listOf(span)
            buildList {
                if (span.start < start) add(span.copy(end = start))
                val middleStart = maxOf(span.start, start)
                val middleEnd = minOf(span.end, end)
                span.copy(colorArgb = null).takeIf { !it.emptyStyle() && middleStart < middleEnd }?.let {
                    add(it.copy(start = middleStart, end = middleEnd))
                }
                if (span.end > end) add(span.copy(start = end))
            }
        }
        return block.copy(
            spans = if (color == null) cleared
            else cleared + RichSpan(start, end, colorArgb = color.toArgb().toLong()),
        )
    }
    val enabled = block.spans.any { span ->
        span.start <= start && span.end >= end && when (kind) {
            FormatKind.BOLD -> span.bold
            FormatKind.ITALIC -> span.italic
            FormatKind.UNDERLINE -> span.underline
            FormatKind.STRIKE -> span.strikeThrough
            FormatKind.COLOR -> false
        }
    }
    if (!enabled) {
        val added = when (kind) {
            FormatKind.BOLD -> RichSpan(start, end, bold = true)
            FormatKind.ITALIC -> RichSpan(start, end, italic = true)
            FormatKind.UNDERLINE -> RichSpan(start, end, underline = true)
            FormatKind.STRIKE -> RichSpan(start, end, strikeThrough = true)
            FormatKind.COLOR -> error("handled above")
        }
        return block.copy(spans = block.spans + added)
    }
    val stripped = block.spans.flatMap { span ->
        if (span.end <= start || span.start >= end || !span.has(kind)) return@flatMap listOf(span)
        buildList {
            if (span.start < start) add(span.copy(end = start))
            val middleStart = maxOf(span.start, start)
            val middleEnd = minOf(span.end, end)
            span.without(kind).takeIf { !it.emptyStyle() && middleStart < middleEnd }?.let {
                add(it.copy(start = middleStart, end = middleEnd))
            }
            if (span.end > end) add(span.copy(start = end))
        }
    }
    return block.copy(spans = stripped)
}

private fun RichSpan.has(kind: FormatKind): Boolean = when (kind) {
    FormatKind.BOLD -> bold
    FormatKind.ITALIC -> italic
    FormatKind.UNDERLINE -> underline
    FormatKind.STRIKE -> strikeThrough
    FormatKind.COLOR -> colorArgb != null
}

private fun RichSpan.without(kind: FormatKind): RichSpan = when (kind) {
    FormatKind.BOLD -> copy(bold = false)
    FormatKind.ITALIC -> copy(italic = false)
    FormatKind.UNDERLINE -> copy(underline = false)
    FormatKind.STRIKE -> copy(strikeThrough = false)
    FormatKind.COLOR -> copy(colorArgb = null)
}

private fun RichSpan.emptyStyle(): Boolean =
    !bold && !italic && !underline && !strikeThrough && colorArgb == null

private fun TextRange.constrain(minimum: Int, maximum: Int): TextRange =
    TextRange(start.coerceIn(minimum, maximum), end.coerceIn(minimum, maximum))
