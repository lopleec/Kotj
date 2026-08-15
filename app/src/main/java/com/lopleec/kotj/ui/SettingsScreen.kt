@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lopleec.kotj.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lopleec.kotj.data.AppLanguage
import com.lopleec.kotj.data.AppSettings
import com.lopleec.kotj.data.ThemeMode
import com.lopleec.kotj.data.NoteSort
import com.lopleec.kotj.BuildConfig

private enum class SettingsChoice { LANGUAGE, THEME, TRASH, SORT }

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onOpenAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    val text = LocalAppStrings.current
    val context = LocalContext.current
    val systemUnlockAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    var choice by remember { mutableStateOf<SettingsChoice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text("设置", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            item { SectionTitle(text("外观与语言", "Appearance & language")) }
            item {
                ListItem(
                    headlineContent = { Text(text("语言", "Language")) },
                    supportingContent = { Text(settings.language.label(text)) },
                    leadingContent = { Icon(Icons.Outlined.Language, null) },
                    modifier = Modifier.clickable { choice = SettingsChoice.LANGUAGE },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(text("主题", "Theme")) },
                    supportingContent = { Text(settings.themeMode.label(text)) },
                    leadingContent = { Icon(Icons.Outlined.DarkMode, null) },
                    modifier = Modifier.clickable { choice = SettingsChoice.THEME },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(text("动态配色", "Dynamic color")) },
                    supportingContent = { Text(text("使用系统壁纸配色", "Use colors from your wallpaper")) },
                    leadingContent = { Icon(Icons.Outlined.AutoAwesome, null) },
                    trailingContent = {
                        Switch(
                            checked = settings.useDynamicColor,
                            onCheckedChange = { onUpdate(settings.copy(useDynamicColor = it)) },
                        )
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionTitle(text("安全", "Security")) }
            item {
                ListItem(
                    headlineContent = { Text(text("使用系统解锁", "Use system unlock")) },
                    supportingContent = {
                        Text(
                            if (systemUnlockAvailable) {
                                text(
                                    "直接用设备的指纹、人脸或锁屏凭据加密、解锁和确认手动删除，不再创建独立密码",
                                    "Use device biometrics or your screen lock directly to encrypt, unlock, and confirm manual deletion; no separate password",
                                )
                            } else {
                                text("需要 Android 11 及已设置的安全锁屏", "Requires Android 11 and a secure screen lock")
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Fingerprint, null) },
                    trailingContent = {
                        Switch(
                            checked = settings.useSystemUnlock && systemUnlockAvailable,
                            onCheckedChange = { onUpdate(settings.copy(useSystemUnlock = it)) },
                            enabled = systemUnlockAvailable,
                        )
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionTitle(text("备忘录", "Notes")) }
            item {
                ListItem(
                    headlineContent = { Text(text("备忘录排序", "Note sorting")) },
                    supportingContent = { Text(settings.noteSort.label(text)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Sort, null) },
                    modifier = Modifier.clickable { choice = SettingsChoice.SORT },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(text("按日期分组", "Group by date")) },
                    supportingContent = { Text(text("显示今天、昨天、最近日期、月份和年份", "Show Today, Yesterday, recent periods, months, and years")) },
                    leadingContent = { Icon(Icons.Outlined.ViewAgenda, null) },
                    trailingContent = {
                        Switch(
                            checked = settings.groupNotesByDate,
                            onCheckedChange = { onUpdate(settings.copy(groupNotesByDate = it)) },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(text("删除前确认", "Confirm before deleting")) },
                    supportingContent = { Text(text("避免误删备忘录", "Prevent accidental deletion")) },
                    leadingContent = { Icon(Icons.Outlined.WarningAmber, null) },
                    trailingContent = {
                        Switch(
                            checked = settings.confirmBeforeDelete,
                            onCheckedChange = { onUpdate(settings.copy(confirmBeforeDelete = it)) },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(text("最近删除保留时长", "Recently deleted retention")) },
                    supportingContent = { Text(retentionLabel(settings.trashRetentionDays, text)) },
                    leadingContent = { Icon(Icons.Outlined.DeleteSweep, null) },
                    modifier = Modifier.clickable { choice = SettingsChoice.TRASH },
                )
            }
            item { HorizontalDivider() }
            item { SectionTitle(text("高级", "Advanced")) }
            item {
                ListItem(
                    headlineContent = { Text(text("高级设置（试验性）", "Advanced settings (experimental)")) },
                    supportingContent = { Text(text("云端备份等可选功能", "Optional features such as cloud backup")) },
                    leadingContent = { Icon(Icons.Outlined.Science, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    modifier = Modifier.clickable(onClick = onOpenAdvanced),
                )
            }
            item { HorizontalDivider() }
            item { SectionTitle(text("关于", "About")) }
            item {
                ListItem(
                    headlineContent = { Text("Kotj") },
                    supportingContent = {
                        Text(
                            text(
                                "版本 ${BuildConfig.VERSION_NAME} · 本地离线备忘录",
                                "Version ${BuildConfig.VERSION_NAME} · Offline local notes",
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                )
            }
        }
    }

    when (choice) {
        SettingsChoice.LANGUAGE -> ChoiceSheet(
            title = text("语言", "Language"),
            options = AppLanguage.entries.map { it to it.label(text) },
            selected = settings.language,
            onDismiss = { choice = null },
            onSelect = { onUpdate(settings.copy(language = it)); choice = null },
        )
        SettingsChoice.THEME -> ChoiceSheet(
            title = text("主题", "Theme"),
            options = ThemeMode.entries.map { it to it.label(text) },
            selected = settings.themeMode,
            onDismiss = { choice = null },
            onSelect = { onUpdate(settings.copy(themeMode = it)); choice = null },
        )
        SettingsChoice.TRASH -> ChoiceSheet(
            title = text("最近删除保留时长", "Recently deleted retention"),
            options = listOf(7, 30, 90, 0).map { it to retentionLabel(it, text) },
            selected = settings.trashRetentionDays,
            onDismiss = { choice = null },
            onSelect = { onUpdate(settings.copy(trashRetentionDays = it)); choice = null },
        )
        SettingsChoice.SORT -> ChoiceSheet(
            title = text("备忘录排序", "Note sorting"),
            options = NoteSort.entries.map { it to it.label(text) },
            selected = settings.noteSort,
            onDismiss = { choice = null },
            onSelect = { onUpdate(settings.copy(noteSort = it)); choice = null },
        )
        null -> Unit
    }
}

private fun NoteSort.label(text: AppStrings): String = when (this) {
    NoteSort.UPDATED -> text("按日期", "By date")
    NoteSort.TITLE -> text("按首字母", "Alphabetically")
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun <T> ChoiceSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp, 12.dp))
        options.forEach { (value, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(selected = value == selected, onClick = null) },
                modifier = Modifier.clickable { onSelect(value) },
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun AppLanguage.label(text: AppStrings): String = when (this) {
    AppLanguage.SYSTEM -> text("跟随系统", "System default")
    AppLanguage.CHINESE -> "简体中文"
    AppLanguage.ENGLISH -> "English"
}

private fun ThemeMode.label(text: AppStrings): String = when (this) {
    ThemeMode.SYSTEM -> text("跟随系统", "System default")
    ThemeMode.LIGHT -> text("浅色", "Light")
    ThemeMode.DARK -> text("深色", "Dark")
}

private fun retentionLabel(days: Int, text: AppStrings): String = when (days) {
    7 -> text("7 天", "7 days")
    30 -> text("30 天", "30 days")
    90 -> text("90 天", "90 days")
    else -> text("永久保留", "Keep forever")
}
