package com.lopleec.kotj

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.lopleec.kotj.security.SystemUnlockStore
import com.lopleec.kotj.ui.KotjApp
import com.lopleec.kotj.ui.AppStrings
import com.lopleec.kotj.ui.LocalAppStrings
import com.lopleec.kotj.ui.NotesViewModel
import com.lopleec.kotj.ui.SecureWindowEffect
import com.lopleec.kotj.ui.SecureWindowGuard
import com.lopleec.kotj.ui.isEnglish
import com.lopleec.kotj.ui.theme.KotjTheme

class MainActivity : ComponentActivity() {
    private val notesViewModel: NotesViewModel by viewModels()
    private val systemUnlockStore by lazy { SystemUnlockStore(this) }
    private var backgroundSecureHold = false
    private val releaseBackgroundSecureHold = Runnable {
        if (backgroundSecureHold) {
            SecureWindowGuard.release(window)
            backgroundSecureHold = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = notesViewModel.state
            val settings = state.settings
            val secureContentVisible = state.editor?.encrypted == true || state.unlockNoteId != null
            SecureWindowEffect(secureContentVisible)
            LaunchedEffect(state.editor?.noteId, settings.useSystemUnlock) {
                val editor = state.editor
                if (
                    settings.useSystemUnlock && editor?.encrypted == true &&
                    !editor.password.isNullOrEmpty() && !systemUnlockStore.hasPassword(editor.noteId)
                ) {
                    enrollSystemUnlock(editor.noteId, editor.password)
                }
            }
            KotjTheme(themeMode = settings.themeMode, useDynamicColor = settings.useDynamicColor) {
                CompositionLocalProvider(LocalAppStrings provides AppStrings(isEnglish(settings.language))) {
                    KotjApp(
                        viewModel = notesViewModel,
                        onSystemUnlock = ::requestSystemUnlock,
                        onSystemEncrypt = ::requestSystemEncryption,
                        onSystemDeleteEditor = ::requestSystemDeleteEditor,
                        onSystemMoveToTrash = ::requestSystemMoveToTrash,
                        onSystemDeleteForever = ::requestSystemDeleteForever,
                    )
                }
            }
        }
        notesViewModel.openExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notesViewModel.openExternalIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        notesViewModel.cancelEncryptedAutoLock()
        if (backgroundSecureHold) {
            window.decorView.post(releaseBackgroundSecureHold)
        }
    }

    override fun onStop() {
        window.decorView.removeCallbacks(releaseBackgroundSecureHold)
        if (notesViewModel.state.editor?.encrypted == true && !backgroundSecureHold) {
            SecureWindowGuard.acquire(window)
            backgroundSecureHold = true
        }
        notesViewModel.scheduleEncryptedAutoLock()
        super.onStop()
    }

    private fun requestSystemUnlock(noteId: String) {
        requestSystemAuthorization(
            noteId = noteId,
            onAuthorized = notesViewModel::unlock,
            onCancelled = notesViewModel::dismissUnlock,
        )
    }

    private fun requestSystemDeleteEditor(noteId: String) {
        requestSystemAuthorization(noteId, notesViewModel::moveEditorToTrash) {}
    }

    private fun requestSystemMoveToTrash(noteId: String) {
        requestSystemAuthorization(
            noteId,
            { password -> notesViewModel.moveNoteToTrash(noteId, password) },
            {},
        )
    }

    private fun requestSystemDeleteForever(noteId: String) {
        requestSystemAuthorization(
            noteId,
            { password -> notesViewModel.deleteForever(noteId, password) },
            {},
        )
    }

    private fun requestSystemAuthorization(
        noteId: String,
        onAuthorized: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onCancelled()
            notesViewModel.showMessage("此设备不支持系统解锁", "System unlock is not supported on this device")
            return
        }
        requestSystemAuthorizationApi30(noteId, onAuthorized, onCancelled)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestSystemAuthorizationApi30(
        noteId: String,
        onAuthorized: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val systemOnly = systemUnlockStore.isSystemOnly(noteId)
        val cipher = systemUnlockStore.newDecryptionCipher(noteId)
        if (cipher == null) {
            if (!systemOnly) systemUnlockStore.remove(noteId)
            onCancelled()
            notesViewModel.showMessage(
                if (systemOnly) "系统解锁密钥已失效，无法解密这篇备忘录" else "系统解锁信息已失效；再次打开可输入原密码",
                if (systemOnly) "The system unlock key expired, so this note cannot be decrypted" else "System unlock expired; open it again to enter the original password",
            )
            return
        }
        authenticateWithSystem(
            cipher = cipher,
            title = localized("解锁加密备忘录", "Unlock encrypted note"),
            subtitle = localized("使用指纹、人脸或锁屏凭据", "Use biometrics or your screen lock"),
            reportCancellation = false,
            onCancelled = onCancelled,
        ) { authenticatedCipher ->
            runCatching { systemUnlockStore.recoverPassword(noteId, authenticatedCipher) }
                .onSuccess(onAuthorized)
                .onFailure {
                    if (!systemOnly) systemUnlockStore.remove(noteId)
                    onCancelled()
                    notesViewModel.showMessage(
                        if (systemOnly) "系统解锁密钥已失效，无法解密这篇备忘录" else "系统解锁信息已失效；再次打开可输入原密码",
                        if (systemOnly) "The system unlock key expired, so this note cannot be decrypted" else "System unlock expired; open it again to enter the original password",
                    )
                }
        }
    }

    private fun requestSystemEncryption(noteId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            notesViewModel.showMessage("此设备不支持系统解锁", "System unlock is not supported on this device")
            return
        }
        requestSystemEncryptionApi30(noteId)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestSystemEncryptionApi30(noteId: String) {
        val editor = notesViewModel.state.editor
        if (editor == null || editor.noteId != noteId || editor.encrypted) return
        val cipher = runCatching { systemUnlockStore.newEncryptionCipher() }.getOrElse {
            notesViewModel.showMessage("无法启用系统解锁", "Could not enable system unlock")
            return
        }
        authenticateWithSystem(
            cipher = cipher,
            title = localized("加密这篇备忘录", "Encrypt this note"),
            subtitle = localized("使用指纹、人脸或锁屏凭据", "Use biometrics or your screen lock"),
            reportCancellation = false,
        ) { authenticatedCipher ->
            val password = systemUnlockStore.generateRandomPassword()
            runCatching {
                systemUnlockStore.savePassword(
                    noteId = noteId,
                    password = password.toCharArray(),
                    authenticatedCipher = authenticatedCipher,
                    systemOnly = true,
                )
            }.onSuccess {
                notesViewModel.encryptEditor(password) { encrypted ->
                    if (!encrypted) systemUnlockStore.remove(noteId)
                }
            }.onFailure {
                systemUnlockStore.remove(noteId)
                notesViewModel.showMessage("无法保存系统解锁信息", "Could not save system unlock data")
            }
        }
    }

    private fun enrollSystemUnlock(noteId: String, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || systemUnlockStore.hasPassword(noteId)) return
        enrollSystemUnlockApi30(noteId, password.toCharArray())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun enrollSystemUnlockApi30(noteId: String, password: CharArray) {
        val cipher = runCatching { systemUnlockStore.newEncryptionCipher() }.getOrElse {
            password.fill('\u0000')
            notesViewModel.showMessage("无法启用系统解锁", "Could not enable system unlock")
            return
        }
        authenticateWithSystem(
            cipher = cipher,
            title = localized("为这篇备忘录启用系统解锁", "Enable system unlock for this note"),
            subtitle = localized("验证指纹、人脸或锁屏凭据", "Verify biometrics or your screen lock"),
            reportCancellation = false,
            onCancelled = { password.fill('\u0000') },
        ) { authenticatedCipher ->
            runCatching { systemUnlockStore.savePassword(noteId, password, authenticatedCipher) }
                .onSuccess {
                    notesViewModel.showMessage("已为这篇备忘录启用系统解锁", "System unlock enabled for this note")
                }
                .onFailure {
                    password.fill('\u0000')
                    notesViewModel.showMessage("无法保存系统解锁信息", "Could not save system unlock data")
                }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticateWithSystem(
        cipher: javax.crypto.Cipher,
        title: String,
        subtitle: String,
        reportCancellation: Boolean,
        onCancelled: () -> Unit = {},
        onSuccess: (javax.crypto.Cipher) -> Unit,
    ) {
        val cancellationSignal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        onCancelled()
                        notesViewModel.showMessage("系统认证未返回密钥", "System authentication did not return a key")
                    } else {
                        onSuccess(authenticatedCipher)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancelled()
                    if (reportCancellation || !isCancellationError(errorCode)) {
                        notesViewModel.showMessage("系统解锁失败：$errString", "System unlock failed: $errString")
                    }
                }
            }
        runCatching {
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                cancellationSignal,
                mainExecutor,
                callback,
            )
        }.onFailure {
            onCancelled()
            notesViewModel.showMessage("无法启动系统认证", "Could not start system authentication")
        }
    }

    private fun localized(chinese: String, english: String): String =
        if (isEnglish(notesViewModel.state.settings.language)) english else chinese

    @RequiresApi(Build.VERSION_CODES.P)
    private fun isCancellationError(errorCode: Int): Boolean =
        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
            errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
}
