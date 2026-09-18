# Edge20Pro Camera2 Vendor Key Probe

This is a small native Java app for the Edge 20 Pro Camera2 HAL investigation. It does **not** capture photos. It only reads `CameraCharacteristics` and reports:

- all Camera2 camera IDs exposed by the phone
- lens facing / hardware level / capabilities
- `getAvailableCaptureRequestKeys()` and Java type
- API 28+: `getAvailableSessionKeys()`
- API 28+: `getAvailablePhysicalCameraRequestKeys()`
- logical multi-camera physical IDs
- non-`android.*` and non-`com.android.*` keys marked as `[VENDOR]`

The app writes `Camera2VendorKeyDump.txt` into its app-specific Documents directory and can also share the report as text.

## You do not need Android Studio

There are two practical build paths.

### Path A — GitHub Actions (no Android build environment on your PC)

1. Create an empty GitHub repository.
2. Upload the contents of this folder to the repository, including `.github/workflows/build-apk.yml`.
3. Open **Actions → Build APK**.
4. Run **Build workflow** (or push a commit to trigger it).
5. Open the completed workflow run and download the artifact named `Edge20Pro-Camera2-VendorKeyProbe-debug`.
6. Inside the artifact is `app-debug.apk`, ready to install on the phone.

The workflow uses Java 17, Android SDK Platform 35 / Build Tools 35.0.1, and Gradle 8.9. AGP 8.7.3 requires Gradle 8.9 or newer.

### Path B — Windows one-click local build

On Windows 10/11 with normal internet access, right-click `build.ps1` and run it with PowerShell, or from PowerShell in this folder run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build.ps1
```

The script installs only what is needed into `%LOCALAPPDATA%\Edge20Pro-Camera2-Probe\tools` and does not require Android Studio. If Java is missing but `winget` is available, it will install Temurin JDK 17 automatically.

The resulting APK is:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## After installing on the Edge 20 Pro

Open the app, tap **Run Probe**, and then use **Share Report** to send `Camera2VendorKeyDump.txt` back for analysis.

The important part for the current investigation is the `[VENDOR]` section for Camera IDs 0/1/2/3, plus any session-key and physical-camera request-key entries.

### Build versions

- Android Gradle Plugin: 8.7.3
- Gradle: 8.9
- compileSdk / targetSdk: 35
- minSdk: 23
