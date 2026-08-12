package com.lopleec.kotj.data

import android.content.Context
import androidx.core.content.edit

enum class AppLanguage { SYSTEM, CHINESE, ENGLISH }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NoteSort { UPDATED, TITLE }

data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val trashRetentionDays: Int = 30,
    val confirmBeforeDelete: Boolean = true,
    val useSystemUnlock: Boolean = false,
    val noteSort: NoteSort = NoteSort.UPDATED,
    val groupNotesByDate: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        language = enumValueOrDefault(preferences.getString("language", null), AppLanguage.SYSTEM),
        themeMode = enumValueOrDefault(preferences.getString("theme_mode", null), ThemeMode.SYSTEM),
        useDynamicColor = preferences.getBoolean("dynamic_color", true),
        trashRetentionDays = preferences.getInt("trash_retention_days", 30),
        confirmBeforeDelete = preferences.getBoolean("confirm_before_delete", true),
        useSystemUnlock = preferences.getBoolean("use_system_unlock", false),
        noteSort = enumValueOrDefault(preferences.getString("note_sort", null), NoteSort.UPDATED),
        groupNotesByDate = preferences.getBoolean("group_notes_by_date", false),
    )

    fun save(settings: AppSettings) {
        preferences.edit {
            putString("language", settings.language.name)
            putString("theme_mode", settings.themeMode.name)
            putBoolean("dynamic_color", settings.useDynamicColor)
            putInt("trash_retention_days", settings.trashRetentionDays)
            remove("show_note_preview")
            putBoolean("confirm_before_delete", settings.confirmBeforeDelete)
            putBoolean("use_system_unlock", settings.useSystemUnlock)
            putString("note_sort", settings.noteSort.name)
            putBoolean("group_notes_by_date", settings.groupNotesByDate)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}
