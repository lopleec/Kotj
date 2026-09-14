<p align="center">
  <strong>English</strong> | <a href="README_zh-CN.md">简体中文</a>
</p>

<div align="center">
  <img src="docs/readme-icon.svg" width="112" alt="Kotj icon">
  <h1>Kotj</h1>
  <p><strong>A complete, local-first notes app for Android</strong></p>
  <p>Material Design 3 · iOS Notes-inspired structure · Rich text editing · Local encryption</p>

  [![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Latest release](https://img.shields.io/github/v/release/lopleec/Kotj)](https://github.com/lopleec/Kotj/releases/latest)
  [![GPL-3.0](https://img.shields.io/github/license/lopleec/Kotj)](LICENSE)
</div>

Kotj is a full-featured native Android notes app with a clean Material Design 3 interface. Its folders, note lists, and editing flow take inspiration from iOS Notes, with Kotlin and Jetpack Compose bringing the experience to Android.

Write a quick thought or a longer document with rich text, images, tables, and checklists. Keep notes organized with folders, search, and pinning, and take them with you through multi-format export. Everyday use is local and requires no account; encrypted Google Drive backup is an optional experimental feature.

## Highlights

- **Built for Android:** Native Kotlin and Jetpack Compose, supporting Android 8.0 and later
- **Material Design 3:** MD3 components, dynamic color, light and dark themes, and platform-native system interactions
- **iOS Notes-inspired structure:** Familiar folders, note lists, recently deleted items, and a clean continuous editing flow
- **Complete feature set:** Rich text, mixed text and images, tables, lists, checklists, search, pinning, import, export, and encryption
- **Privacy first:** Local-only by default, no telemetry, and optional client-side encrypted Google Drive backup

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
- Updates signed with the same key can be installed over the existing app to retain local data
- Debug and official builds use different signatures; export important notes before switching between them

## Complete feature set

### Editing

- Start with a blank canvas: any line can use body or title styling, and title styles can be used more than once anywhere in a note
- Bold, italic, underline, strikethrough, and text color, including combined underline and strikethrough
- Body text, titles, headings, subheadings, numbered lists, bullet lists, and native checkbox tasks
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

### Appearance and language

- English and Simplified Chinese, with a system-language option
- Light, dark, and system-following themes
- Android 12+ dynamic color
- Configurable note sorting, date grouping, and Recently Deleted retention

### Optional Google Drive backup (experimental)

- Enable it in **Settings → Advanced settings (experimental)**; it is disabled by default, and local use requires no Google sign-in
- Uses the hidden Drive `appDataFolder` with the minimum `drive.appdata` scope
- Automatic, debounced backups after local changes plus periodic connected-device backup work
- AES-256-GCM encrypted snapshots containing the note database, folders, deletion state, and attachments; the portable recovery key is stored in the same account's private app-data folder
- Resumable uploads for image-rich backups, manual **Back up now**, and Google Account switching; cancelling an account switch preserves the current connection
- On a new or reinstalled device, Kotj detects and pairs the existing snapshot with its account recovery key, blocks uploads until recovery finishes, and merges local and cloud notes after Google authorization without a separate backup password
- Password-era backups are migrated automatically by their original installation after its next successful backup; an unmigrated legacy backup cannot be recovered passwordlessly on a different installation
- Merge keeps content unique to either side; notes sharing an ID use the newer version (ties stay local), while folders, Recently Deleted state, and attachments follow the selected note version
- Before every upload, Kotj checks the server revision and blocks a stale device from overwriting a backup changed by another installation until the user merges it
- When disabling automatic backup, either preserve the cloud backup and sign-in or permanently delete all Kotj app-data files, revoke access, and disconnect while keeping local notes
- **Local + cloud** and experimental **Cloud primary** modes; a necessary local working cache remains for reliable editing and offline access

### Privacy and security

- The `INTERNET` permission is used only when the optional Google Drive feature is enabled; no telemetry is collected
- With Google Drive backup off, Kotj creates no cloud authorization request, network backup, or WorkManager backup job
- Cleartext network traffic and Android system backup/device migration remain disabled
- Use a standalone encryption password or Android's system biometric/device credential authentication
- Manually deleting an encrypted note requires authentication; expired Recently Deleted items can be cleaned automatically
- Encrypted notes do not store titles, bodies, or search indexes in plaintext
- Screenshots and recent-app previews are blocked while encrypted content is open

## Encryption design

Kotj derives an AES-256 key from the note password with PBKDF2-HMAC-SHA256 using a unique random salt and 210,000 iterations, then encrypts data with AES-GCM. Encrypted images use separate random salts and IVs, with internal filenames included as additional authenticated data. System unlock stores a wrapped random password in Android Keystore and requires strong biometrics or device credentials for each decryption.

Google Drive backups use a random AES-256 key to encrypt the complete logical snapshot before any network upload. The local key copy is wrapped by a non-exportable Android Keystore key; a portable copy is stored beside the snapshot in that Google Account's private `appDataFolder`, so Google Account authorization—not a separate backup password—is the recovery boundary. Access tokens are short-lived and are never persisted by Kotj.

The project applies security hardening appropriate for a local notes app, but it has not undergone an independent third-party security audit. When reporting a security issue, do not include real notes, passwords, or key material in a public Issue.

## Technology

- Kotlin
- Jetpack Compose
- Material 3 with Android 12+ dynamic color
- Android SQLite
- WorkManager and Google Identity Services authorization
- Google Drive `appDataFolder` REST API
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

For Google Drive in your own build, register an Android OAuth client for `com.lopleec.kotj` and your signing certificate SHA-1 in the Google Cloud project used by the app. Android OAuth client IDs and project IDs are public application configuration; OAuth client secrets and signing credentials must remain private.

<details>
<summary>Build a signed release</summary>

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

</details>

## Project structure

```text
app/src/main/java/com/lopleec/kotj/
├── backup/     # Optional encrypted Google Drive backup
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

Kotj is licensed under the [GNU General Public License v3.0](LICENSE). See the included license for its terms.
