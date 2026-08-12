<p align="center">
  <strong>English</strong> | <a href="README_zh-CN.md">简体中文</a>
</p>

<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_source.png" width="112" alt="Kotj icon" style="border-radius: 24px;">
  <h1>Kotj</h1>
  <p><strong>A complete, local-first notes app for Android</strong></p>
  <p>Material Design 3 · iOS Notes-inspired structure · Rich text editing · Local encryption</p>

  [![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Latest release](https://img.shields.io/github/v/release/lopleec/Kotj)](https://github.com/lopleec/Kotj/releases/latest)
  [![GPL-3.0](https://img.shields.io/github/license/lopleec/Kotj)](LICENSE)
</div>

Kotj is a full-featured native notes app built for Android. It takes inspiration from the clear information architecture and editing flow of Apple Notes while using Jetpack Compose and Material Design 3 to feel at home on Android—not as a simple visual clone of iOS.

From quick thoughts to long, image-rich documents, Kotj includes rich text, images, tables, checklists, folders, global search, recently deleted items, encryption, and multi-format import and export. It requests no network permission; notes, images, folders, and settings remain in the app's private storage on your device.

## Highlights

- **Built for Android:** Native Kotlin and Jetpack Compose, supporting Android 8.0 and later
- **Material Design 3:** MD3 components, dynamic color, light and dark themes, and platform-native system interactions
- **iOS Notes-inspired structure:** Familiar folders, note lists, recently deleted items, and a clean continuous editing flow
- **Complete feature set:** Rich text, mixed text and images, tables, lists, checklists, search, pinning, import, export, and encryption
- **Privacy first:** Fully local storage with no network permission, note uploads, or telemetry

## Screenshots

<p align="center">
  <img src="docs/screenshots/all-notes.png" width="205" alt="All notes">
  <img src="docs/screenshots/folders.png" width="205" alt="Folders and navigation">
  <img src="docs/screenshots/editor.png" width="205" alt="Rich text editor">
  <img src="docs/screenshots/settings.png" width="205" alt="Settings">
</p>

> [!IMPORTANT]
> Encryption passwords cannot be recovered. If you forget a standalone password, lose access to the system unlock key, or clear the app's data, the affected encrypted notes may be permanently inaccessible.

## Download

Download the latest formally signed APK from [GitHub Releases](https://github.com/lopleec/Kotj/releases/latest).

- Android 8.0 (API 26) or later
- Package name: `com.lopleec.kotj`
- Android may ask you to allow your browser or file manager to install unknown apps
- A formally signed build cannot replace an older Debug build because their signatures differ; export important notes before migrating

## Complete feature set

### Editing

- Start typing a title immediately, then press Enter to continue with the body; the title can also be converted into ordinary body text
- Bold, italic, underline, strikethrough, and text color
- Body text, headings, quotes, numbered lists, bullet lists, and native checkbox tasks
- Tables, dividers, and images through the system Photo Picker
- Images retain their original aspect ratio, and text can continue directly after images or other embedded items
- Undo, redo, find in note, result highlighting, and navigation
- Empty notes are automatically discarded when you leave

### Organization and search

- Global search with result highlighting
- Custom folders, note moving, and pinning
- Sort by last modified date or title
- Optional date sections for Today, Yesterday, Last 7 Days, Last 30 Days, months, and years
- Recently Deleted with restore, permanent deletion, and configurable automatic cleanup

### Import and export

- Import TXT, Markdown, RTF, and DOCX
- Export DOCX, Markdown, and plain text
- DOCX images keep their aspect ratio and are written as a stream to reduce memory use for large documents

### Privacy and security

- No `INTERNET` permission, note uploads, or telemetry
- Cleartext network traffic, cloud backup, and device-to-device migration are disabled
- Use a standalone encryption password or Android's system biometric/device credential authentication
- Manually deleting an encrypted note requires authentication; expired Recently Deleted items can be cleaned automatically
- Encrypted notes do not store titles, bodies, or search indexes in plaintext
- Screenshots and recent-app previews are blocked while encrypted content is open

## Encryption design

Kotj derives an AES-256 key from the note password with PBKDF2-HMAC-SHA256 using a unique random salt and 210,000 iterations, then encrypts data with AES-GCM. Encrypted images use separate random salts and IVs, with internal filenames included as additional authenticated data. System unlock stores a wrapped random password in Android Keystore and requires strong biometrics or device credentials for each decryption.

The project applies security hardening appropriate for a local notes app, but it has not undergone an independent third-party security audit. When reporting a security issue, do not include real notes, passwords, or key material in a public Issue.

## Technology

- Kotlin
- Jetpack Compose
- Material 3 with Android 12+ dynamic color
- Android SQLite
- Kotlin Coroutines
- Android Keystore, BiometricPrompt, and the system Photo Picker

## Build from source

### Requirements

- JDK 21
- Android SDK 36.1
- Android Studio or Android SDK command-line tools

```bash
git clone https://github.com/lopleec/Kotj.git
cd Kotj
./gradlew clean :app:lintDebug :app:assembleDebug
```

The Debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Build a signed release

Release builds enable R8 optimization, obfuscation, and resource shrinking, and never fall back to a Debug signature. Store the keystore outside the project and provide these values through the user-level `~/.gradle/gradle.properties` file or environment variables with the same names:

```properties
KOTJ_RELEASE_STORE_FILE=/absolute/path/to/kotj-release.jks
KOTJ_RELEASE_STORE_PASSWORD=your-store-password
KOTJ_RELEASE_KEY_ALIAS=your-key-alias
KOTJ_RELEASE_KEY_PASSWORD=your-key-password
```

```bash
./gradlew clean :app:lintRelease :app:assembleRelease :app:bundleRelease
```

Without complete signing configuration, Gradle produces unsigned artifacts that are not ready for distribution or direct installation. Never commit keystores, passwords, `local.properties`, or user-level Gradle configuration.

## Project structure

```text
app/src/main/java/com/lopleec/kotj/
├── data/       # SQLite, settings, and attachment storage
├── export/     # DOCX, Markdown, and TXT export
├── importer/   # TXT, Markdown, RTF, and DOCX import
├── model/      # Note and editor data models
├── security/   # Passwords, encrypted attachments, and system unlock
└── ui/         # Compose Material 3 interface
```

## Contributing

Issues and pull requests are welcome. Before submitting code, make sure that:

1. No keystores, passwords, personal notes, or other sensitive data are included.
2. `./gradlew :app:lintDebug :app:assembleDebug` passes.
3. New features account for both English and Chinese, light and dark themes, and accessibility descriptions.
4. Changes to storage or encryption formats remain backward compatible and document their migration strategy.

## License

Kotj is released under the [GNU General Public License v3.0](LICENSE). Distributions of modified versions must follow the GPL-3.0 source disclosure and license preservation requirements.
