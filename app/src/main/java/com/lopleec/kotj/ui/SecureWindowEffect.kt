package com.lopleec.kotj.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** Keeps FLAG_SECURE active while any sensitive screen or dialog owns it. */
@Composable
fun SecureWindowEffect(enabled: Boolean = true) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window, enabled) {
        if (enabled && window != null) SecureWindowGuard.acquire(window)
        onDispose {
            if (enabled && window != null) SecureWindowGuard.release(window)
        }
    }
}

object SecureWindowGuard {
    private val owners = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val count = owners[window] ?: 0
        owners[window] = count + 1
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val next = (owners[window] ?: 1) - 1
        if (next <= 0) {
            owners.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            owners[window] = next
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
