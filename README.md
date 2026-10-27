# Virtual Mouse Controller for Android

A lightweight Android application that creates a virtual mouse cursor overlay. You can move the cursor by dragging anywhere on the screen and perform real clicks / edge page transitions via the official AccessibilityService API.

**Target device:** Redmi Note 8 Pro (and any Android 11+ / API 30+ device)  
**No root required.**

## Features

1. **Virtual Mouse**
   - ON/OFF toggle
   - Floating modern cursor that stays visible over other apps
   - Size: Small / Medium / Large / Custom

2. **Touch Movement**
   - Hold and drag anywhere → cursor moves with adjustable sensitivity
   - Cursor stays at last position when finger is lifted
   - When mouse is off, normal touch behaviour is unchanged

3. **Click System**
   - Single tap (while not dragging) sends a real left-click at the current cursor position
   - Uses `AccessibilityService.dispatchGesture` (the only supported public way)

4. **Edge Page Transition**
   - Cursor held at left/right screen edge for ~350 ms triggers a horizontal swipe
   - Toggleable in settings
   - Best on home screens / launchers and apps that support horizontal paging
   - Not guaranteed on every application (Android limitation)

5. **Permissions (explained in-app)**
   - `SYSTEM_ALERT_WINDOW` – draw the cursor over other apps
   - Accessibility Service – inject clicks and swipes

## Technical Limitations (Android Security Model)

- There is **no public non-Accessibility API** to inject clicks into other apps. AccessibilityService is required and must be enabled by the user in system settings.
- Overlay requires the “Display over other apps” permission.
- Edge swipes are best-effort gestures; success depends on the target window.
- Full-screen overlay may conflict with some system navigation gestures on certain ROMs.
- Battery / RAM usage is kept low (foreground service with low-importance notification only while active).

## Build Instructions

### Requirements
- Android Studio Hedgehog or newer (or command-line SDK)
- JDK 17
- Android SDK with API 30–34

### Local build
```bash
# Generate wrapper if needed
gradle wrapper --gradle-version 8.2

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
Push to `main`/`master` or run the workflow manually. The debug APK is uploaded as an artifact named `VirtualMouse-debug`.

## Project Structure

```
VirtualMouseController/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/virtualmouse/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MouseOverlayService.kt
│   │   │   └── MouseAccessibilityService.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── .github/workflows/build.yml
├── build.gradle
├── settings.gradle
└── README.md
```

## How to use

1. Install the APK.
2. Open the app → grant Overlay permission.
3. Open Accessibility settings → enable “Virtual Mouse”.
4. Toggle the mouse ON.
5. Drag anywhere to move the cursor; tap to click.

## License

MIT – feel free to modify and distribute.
