# Android device testing

## Build status and scope

The Android native host and APK are built by two linked GitHub Actions workflows:

1. **Android native host** builds the ARM64 native libraries and runs the native/mobile regression checks.
2. **Android native app prototype** packages those exact libraries into a signed debug APK.

The APK is an installable development build for ARM64 devices. It is not a Play Store/release build. It does not contain a game ROM, ROM-derived title data, or a prebuilt title AOT module. Keep those private and use the app's normal local preparation flow with a legally obtained personal dump. Do not upload ROMs, title modules, saves, or private preparation data to GitHub.

Current Android requirements are API 29 or newer and Vulkan 1.2. A device that does not expose the required Vulkan 1.2 capabilities is not qualified by this build.

## Download and install

1. Open the successful **Android native app prototype** run from the repository's Actions tab.
2. In **Artifacts**, download `Ocarina-ReTuned-Native-Android-APK-<revision>`.
3. Extract the ZIP and install `Ocarina-ReTuned-Native-Android-arm64-debug.apk` on an ARM64 Android device.
4. If Android blocks installation, allow the file manager or browser used for the download to install this one package, then retry.
5. Launch with the device unlocked and keep the app in the foreground during testing.

The debug APK is signed with the Android Gradle debug key. It is intended for development testing, not distribution. Updating an existing installation signed with a different key may require resolving the package-signature conflict; do not uninstall an installation containing data you need to keep.

## First test

1. Confirm the app opens and the Android setup interface is responsive.
2. Use the app's normal local setup/preparation path with your own clean game dump. The APK itself intentionally contains no copyrighted game content.
3. Once the title is prepared, launch into a repeatable scene. For initial diagnostics, use the same scene and settings across runs.
4. In Startup Diagnostics, select **Capture performance on next run**.
5. Launch the game, keep the device awake and the app foregrounded, and run the same scene for approximately two minutes.
6. Exit normally. The diagnostics files are finalized on normal shutdown; force-stop or a crash may leave an incomplete or missing capture.

For the capture controls and metric definitions, see [Android performance capture](Android-performance-capture.md).

## What to send back

Share these two app-private diagnostic outputs, if they were produced:

- `native-runtime-summary.json`
- `vulkan-frame-trace.json`

Do not share the ROM, ROM-derived title data, save files, account information, or unrelated private files. If the Vulkan trace is absent, say so rather than substituting screenshots of FPS counters.

Record the device model, Android version, GPU/driver version if shown by the app, render resolution, and the scene used. Those details let us separate a real rendering bottleneck from device-specific driver behavior.

## Interpretation limits

- A successful APK build proves packaging and compile/link checks, not that the app runs correctly on every device.
- A successful app launch does not prove the title has been prepared or that gameplay works.
- Vulkan timestamps may be unavailable on some drivers. Missing GPU timings are not zero.
- A single-device capture does not establish broad Android compatibility.
- Do not claim stable native frame rate until repeated, foreground, same-scene captures support it.
