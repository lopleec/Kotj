package com.lopleec.kotj.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.lopleec.kotj.data.AppLanguage
import com.lopleec.kotj.model.Category
import java.util.Locale

class AppStrings(val english: Boolean) {
    operator fun invoke(chinese: String, englishText: String): String = if (english) englishText else chinese
}

val LocalAppStrings = staticCompositionLocalOf { AppStrings(false) }

fun isEnglish(language: AppLanguage): Boolean = when (language) {
    AppLanguage.ENGLISH -> true
    AppLanguage.CHINESE -> false
    AppLanguage.SYSTEM -> !Locale.getDefault().language.startsWith("zh")
}

fun Category.localizedName(strings: AppStrings): String = when {
    id == "personal" && name == "个人" -> strings("个人", "Personal")
    id == "work" && name == "工作" -> strings("工作", "Work")
    id == "ideas" && name == "灵感" -> strings("灵感", "Ideas")
    else -> name
}
