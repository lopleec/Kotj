@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lopleec.kotj.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lopleec.kotj.model.BlockType
import com.lopleec.kotj.model.ImportPreview
import com.lopleec.kotj.model.NoteBlock
import com.lopleec.kotj.model.TextKind

@Composable
fun ImportPreviewScreen(
    preview: ImportPreview,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            preview.sourceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            strings("临时预览 · 尚未保存", "Temporary preview · Not saved"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, strings("返回", "Back"))
                    }
                },
                actions = {
                    TextButton(onClick = onSave) {
                        Text(strings("保存备忘录", "Save note"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "preview-title") {
                    Text(
                        preview.document.title.ifBlank { strings("无标题", "Untitled") },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(preview.document.blocks, key = { it.id }) { block ->
                    when (block.type) {
                        BlockType.TEXT -> if (block.textKind == TextKind.CHECKLIST) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = block.checked, onCheckedChange = null, enabled = false)
                                Text(
                                    text = previewAnnotatedText(block),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                                )
                            }
                        } else {
                            Text(
                                text = previewAnnotatedText(block),
                                style = when (block.textKind) {
                                    TextKind.TITLE -> MaterialTheme.typography.headlineMedium
                                    TextKind.HEADING -> MaterialTheme.typography.headlineSmall
                                    TextKind.SUBHEADING -> MaterialTheme.typography.titleLarge
                                    TextKind.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                                    TextKind.CHECKLIST, TextKind.BODY -> MaterialTheme.typography.bodyLarge
                                },
                            )
                        }
                        BlockType.DIVIDER -> HorizontalDivider()
                        BlockType.TABLE -> PreviewTable(block)
                        BlockType.IMAGE -> Text(
                            strings("[图片]", "[Image]") + block.imageCaption.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTable(block: NoteBlock) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Icon(Icons.Outlined.TableChart, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(LocalAppStrings.current("表格", "Table"), style = MaterialTheme.typography.labelLarge)
        }
        block.tableCells.forEach { row ->
            Text(row.joinToString("  |  "), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
        }
    }
}

private fun previewAnnotatedText(block: NoteBlock): AnnotatedString = buildAnnotatedString {
    val offset = length
    append(block.text)
    block.spans.forEach { span ->
        val start = offset + span.start.coerceIn(0, block.text.length)
        val end = offset + span.end.coerceIn(span.start.coerceAtLeast(0), block.text.length)
        if (start >= end) return@forEach
        addStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.underline && span.strikeThrough -> TextDecoration.combine(
                        listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                    )
                    span.underline -> TextDecoration.Underline
                    span.strikeThrough -> TextDecoration.LineThrough
                    else -> null
                },
                color = span.colorArgb?.let { Color(it.toInt()) } ?: Color.Unspecified,
            ),
            start,
            end,
        )
    }
}
