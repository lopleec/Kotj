@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lopleec.kotj.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lopleec.kotj.backup.DriveBackupUiState
import com.lopleec.kotj.data.AppSettings
import com.lopleec.kotj.data.DriveStorageMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AdvancedSettingsScreen(
    settings: AppSettings,
    driveState: DriveBackupUiState,
    onUpdate: (AppSettings) -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromCloud: () -> Unit,
    onDisableKeepingCloud: () -> Unit,
    onDisableAndDeleteCloud: () -> Unit,
    onBack: () -> Unit,
) {
    val text = LocalAppStrings.current
    var restoreConfirmDialog by remember { mutableStateOf(false) }
    var disableBackupDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text("高级设置", "Advanced settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !driveState.restoreInProgress) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, text("返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text(text("试验性功能", "Experimental features")) },
                    supportingContent = {
                        Text(
                            text(
                                "这些功能仍在完善。关闭云端备份时，应用保持原有的纯本地行为，不登录账号、不联网备份，也不创建后台任务。",
                                "These features are still evolving. With cloud backup off, Kotj keeps its original local-only behavior: no account sign-in, network backup, or background work.",
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Science, null) },
                )
            }
            item { HorizontalDivider() }
            item { AdvancedSectionTitle(text("Google Drive", "Google Drive")) }
            item {
                ListItem(
                    headlineContent = { Text(text("自动备份", "Automatic backup")) },
                    supportingContent = {
                        Text(
                            if (settings.googleDriveBackupEnabled) {
                                text("使用 Drive 应用数据目录保存加密备份", "Store encrypted backups in the Drive app data folder")
                            } else if (driveState.accountEmail != null) {
                                text(
                                    "已关闭；云端内容和登录状态仍保留",
                                    "Off; cloud data and sign-in are still preserved",
                                )
                            } else {
                                text("已关闭，所有数据仍只保存在本机", "Off; all data remains local")
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                    trailingContent = {
                        Switch(
                            checked = settings.googleDriveBackupEnabled,
                            // A running backup can always be cancelled by turning the feature off;
                            // only prevent re-enabling while disconnect/deletion is still running.
                            // Cloud merge is transactional and must not race a disable/delete action.
                            enabled = !driveState.restoreInProgress &&
                                (settings.googleDriveBackupEnabled || !driveState.backupInProgress),
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    onUpdate(settings.copy(googleDriveBackupEnabled = true))
                                } else {
                                    disableBackupDialog = true
                                }
                            },
                        )
                    },
                )
            }
            if (driveState.backupInProgress) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (driveState.restoreInProgress) {
                item {
                    Text(
                        text(
                            "正在校验并合并云端内容，请保持此页面打开",
                            "Verifying and merging cloud content; keep this screen open",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }

            if (settings.googleDriveBackupEnabled) {
                if (driveState.accountEmail == null) {
                    item {
                        FilledTonalButton(
                            onClick = onSignIn,
                            enabled = !driveState.backupInProgress,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            Icon(Icons.Outlined.Cloud, null)
                            Spacer(Modifier.width(8.dp))
                            Text(text("登录 Google Drive", "Sign in to Google Drive"))
                        }
                    }
                } else {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text(
                                        "已登录：${driveState.accountDisplayName ?: driveState.accountEmail}",
                                        "Signed in: ${driveState.accountDisplayName ?: driveState.accountEmail}",
                                    ),
                                )
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        if (driveState.accountDisplayName != null) {
                                            append(driveState.accountEmail).append(" · ")
                                        }
                                        append(text("点击切换账号", "Tap to switch account"))
                                    },
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.CloudDone, null) },
                            modifier = Modifier.clickable(
                                enabled = !driveState.backupInProgress,
                                onClick = onSwitchAccount,
                            ),
                        )
                    }
                    if (driveState.remoteBackupAvailable) {
                        item {
                            ListItem(
                                headlineContent = { Text(text("发现云端备份", "Cloud backup available")) },
                                supportingContent = {
                                    Text(
                                        if (driveState.remoteKeyAvailable) {
                                            driveState.remoteBackupModifiedAt?.let { modifiedAt ->
                                                text(
                                                    "备份时间：${formatBackupTime(modifiedAt)} · 登录此账号即可读取并合并",
                                                    "Backup time: ${formatBackupTime(modifiedAt)} · Sign in to this account to read and merge",
                                                )
                                            } ?: text(
                                                "登录此 Google 账号即可读取并合并，无需备份密码",
                                                "Sign in to this Google Account to read and merge without a backup password",
                                            )
                                        } else if (driveState.encryptionReady && !driveState.restoreRequired) {
                                            text(
                                                "账号恢复密钥等待同步，请点击“立即备份”重试",
                                                "The account recovery key is waiting to sync; tap Back up now to retry",
                                            )
                                        } else {
                                            text(
                                                "旧版备份尚未迁移；请先让创建它的原设备升级并完成一次备份",
                                                "Legacy backup not migrated yet; update its original device and let it complete one backup",
                                            )
                                        },
                                    )
                                },
                                leadingContent = { Icon(Icons.Outlined.CloudDownload, null) },
                            )
                        }
                    }
                    if (driveState.remoteBackupAvailable && driveState.remoteKeyAvailable) {
                        item {
                            FilledTonalButton(
                                onClick = { restoreConfirmDialog = true },
                                enabled = !driveState.backupInProgress,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Outlined.CloudDownload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(text("与云端合并", "Merge from cloud"))
                            }
                        }
                    } else if (!driveState.remoteBackupCheckCompleted && !driveState.encryptionReady) {
                        item {
                            FilledTonalButton(
                                onClick = onBackupNow,
                                enabled = !driveState.backupInProgress,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Outlined.CloudDownload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(text("检查云端备份", "Check for cloud backup"))
                            }
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(text("Google 账号恢复", "Google Account recovery")) },
                            supportingContent = {
                                Text(
                                    text(
                                        "备份使用 AES-256-GCM 加密，恢复密钥保存在此账号的私有应用数据目录；无需单独密码",
                                        "Backups use AES-256-GCM; the recovery key is kept in this account's private app-data folder, with no separate password",
                                    ),
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Lock, null) },
                        )
                    }
                    item { AdvancedSectionTitle(text("存储方式", "Storage mode")) }
                    item {
                        val modes = DriveStorageMode.entries
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.driveStorageMode == mode,
                                    onClick = { onUpdate(settings.copy(driveStorageMode = mode)) },
                                    enabled = !driveState.restoreInProgress,
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    label = { Text(mode.label(text)) },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            when (settings.driveStorageMode) {
                                DriveStorageMode.LOCAL_AND_CLOUD -> text(
                                    "本机数据保持为主副本，并自动创建云端加密备份。",
                                    "Local data remains the primary copy, with an automatic encrypted cloud backup.",
                                )
                                DriveStorageMode.CLOUD_ONLY -> text(
                                    "云端作为主副本；为保证编辑和离线安全，设备仍保留必要的本地工作缓存。",
                                    "Cloud is the primary copy; the device still keeps the local working cache required for safe editing and offline access.",
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(text("最近备份", "Last backup")) },
                            supportingContent = {
                                Text(
                                    when {
                                        driveState.lastError != null -> driveState.lastError
                                        driveState.restoreRequired -> text("等待与云端合并", "Waiting for cloud merge")
                                        driveState.lastBackupAt != null -> formatBackupTime(driveState.lastBackupAt)
                                        driveState.encryptionReady -> text("等待首次备份", "Waiting for the first backup")
                                        else -> text("尚未配置", "Not configured")
                                    },
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Storage, null) },
                        )
                    }
                    if (driveState.encryptionReady && !driveState.restoreRequired) {
                        item {
                            FilledTonalButton(
                                onClick = onBackupNow,
                                enabled = !driveState.backupInProgress,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Outlined.CloudUpload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(text("立即备份", "Back up now"))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (restoreConfirmDialog) {
        RestoreBackupDialog(
            onDismiss = { restoreConfirmDialog = false },
            onConfirm = {
                restoreConfirmDialog = false
                onRestoreFromCloud()
            },
        )
    }

    if (disableBackupDialog) {
        AlertDialog(
            onDismissRequest = { disableBackupDialog = false },
            icon = { Icon(Icons.Outlined.Cloud, null) },
            title = { Text(text("关闭自动备份？", "Turn off automatic backup?")) },
            text = {
                Text(
                    text(
                        "无论选择哪一项，本机的全部备忘录都会保留。请选择如何处理 Google Drive 中的加密备份和当前登录状态；删除云端内容后无法恢复。",
                        "All local notes will be kept either way. Choose what to do with the encrypted Google Drive backup and current sign-in; deleted cloud data cannot be recovered.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        disableBackupDialog = false
                        onDisableAndDeleteCloud()
                    },
                ) {
                    Text(
                        text("删除并解除绑定", "Delete and disconnect"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        disableBackupDialog = false
                        onDisableKeepingCloud()
                    },
                ) {
                    Text(text("保留并关闭", "Keep and turn off"))
                }
            },
        )
    }
}

@Composable
private fun RestoreBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val text = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CloudDownload, null) },
        title = { Text(text("与云端备份合并？", "Merge with cloud backup?")) },
        text = {
            Text(
                text(
                    "云端和本机独有的内容都会保留；同一篇备忘录以更新时间较新的版本为准，时间相同则保留本机版本。分组、最近删除和附件会一并合并，无需备份密码，也不会删除云端内容。",
                    "Content unique to either side is kept. When the same note exists on both sides, the newer version wins; ties stay local. Groups, recently deleted notes, and attachments are merged too. No backup password is required, and cloud data is not deleted.",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text("合并", "Merge"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text("取消", "Cancel")) }
        },
    )
}

@Composable
private fun AdvancedSectionTitle(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

private fun DriveStorageMode.label(text: AppStrings): String = when (this) {
    DriveStorageMode.LOCAL_AND_CLOUD -> text("本地 + 云", "Local + cloud")
    DriveStorageMode.CLOUD_ONLY -> text("纯云", "Cloud only")
}

private fun formatBackupTime(timeMillis: Long): String = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timeMillis))
