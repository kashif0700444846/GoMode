# GodMode — Build Instructions

## System Requirements

| Requirement | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android NDK | r26b or newer |
| CMake | 3.22.1 or newer |
| JDK | 17 |
| Gradle | 8.2.2 (bundled) |
| Target Device | Android 10+ (API 29+), rooted |
| Tested On | OnePlus 12 (CPH2581), Android 16, OxygenOS 16, Kernel 6.6.118 |

---

## Step 1 — Install Android Studio and NDK

1. Download and install [Android Studio](https://developer.android.com/studio).
2. Open **SDK Manager** → **SDK Tools** tab.
3. Check **NDK (Side by side)** → version **26.x** or newer.
4. Check **CMake** → version **3.22.1** or newer.
5. Click **Apply** and wait for installation.

---

## Step 2 — Open the Project

1. Launch Android Studio.
2. Click **File → Open** and select the `GodMode/` folder.
3. Wait for Gradle sync to complete. It will download all dependencies automatically.
4. If prompted about NDK version, click **Install NDK** or set the path in `local.properties`:
   ```
   ndk.dir=/path/to/Android/Sdk/ndk/26.x.x
   ```

---

## Step 3 — Build the APK

### Debug Build (recommended for testing)
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build
```
Build → Generate Signed Bundle / APK → APK
```
You will need to create a keystore. Follow the wizard.

### Command Line Build
```bash
cd GodMode
./gradlew assembleDebug
```

---

## Step 4 — Install on Device

### Via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Via USB Transfer
Copy the APK to your phone and install it. You may need to enable **Install from Unknown Sources** in Settings → Security.

---

## Step 5 — First Launch

1. Open **GodMode** on your device.
2. A root permission dialog will appear — **tap Grant** in Magisk/KernelSU.
3. GodMode will automatically:
   - Install the native daemon (`godmoded`) to `/data/local/tmp/`
   - Install the hook library (`libgodmode_hook.so`) to `/data/local/tmp/` and attempt `/system/lib64/`
   - Create a boot persistence script in `/data/adb/service.d/godmode.sh`
   - Start the daemon and begin monitoring

---

## Architecture Overview

```
GodMode App (Kotlin)
    │
    ├── UI Layer (Activities + Fragments)
    │   ├── MainActivity (bottom nav)
    │   ├── DashboardFragment (status + stats)
    │   ├── AppsFragment (app list)
    │   ├── LogsFragment (global access log)
    │   ├── SettingsFragment
    │   ├── AppDetailActivity (per-app spoof config)
    │   ├── LogDetailActivity (per-app logs)
    │   └── SpoofSettingsActivity (device info)
    │
    ├── Data Layer (Room DB)
    │   ├── AppConfig (per-app spoofing rules)
    │   └── AccessLog (all intercepted accesses)
    │
    ├── Service Layer
    │   ├── GodModeDaemonService (foreground, polls daemon)
    │   ├── BootReceiver (auto-start on boot)
    │   └── DaemonMessageReceiver (IPC events)
    │
    └── Native Layer (C/C++ via JNI)
        ├── godmode_jni.so (JNI bridge to Kotlin)
        ├── godmoded (root daemon binary)
        │   ├── process_monitor (detects new app launches)
        │   ├── injector (ptrace + dlopen injection)
        │   ├── config_manager (SQLite config/log store)
        │   └── socket_server (IPC server)
        └── libgodmode_hook.so (injected into target apps)
            ├── binder_hook (intercepts Binder IPC)
            ├── property_hook (intercepts system props)
            └── spoof_engine (generates spoofed values)
```

---

## What GodMode Intercepts

| Data Type | Method | Spoofable |
|---|---|---|
| IMEI | TelephonyManager.getImei() | Yes |
| IMSI | TelephonyManager.getSubscriberId() | Yes |
| Android ID | Settings.Secure.ANDROID_ID | Yes |
| Serial Number | Build.getSerial() / ro.serialno | Yes |
| Phone Number | TelephonyManager.getLine1Number() | Yes |
| SIM Serial | TelephonyManager.getSimSerialNumber() | Yes |
| Network Operator | TelephonyManager.getNetworkOperator() | Yes |
| GPS Location | LocationManager.getLastKnownLocation() | Yes |
| IP Address | NetworkInterface / getifaddrs | Yes |
| MAC Address | WifiInfo.getMacAddress() | Yes |
| Camera Access | CameraManager.openCamera() | Block |
| Microphone Access | AudioRecord / MediaRecorder | Block |
| Contacts | ContentResolver query | Block |
| Calendar | ContentResolver query | Block |
| Clipboard | ClipboardManager.getPrimaryClip() | Block |
| Sensors | SensorManager.registerListener() | Block |
| Advertising ID | AdvertisingIdClient | Yes |
| Build Properties | ro.product.model, ro.product.brand | Yes |
| Network Connections | socket() / connect() | Monitor |

---

## Spoofing Modes

| Mode | Behavior |
|---|---|
| **Disabled** | Pass real value through (monitoring only) |
| **Custom** | Return your manually entered value |
| **Random** | Generate a new random value each session |
| **Block / Empty** | Return null, empty string, or deny access |

---

## Root Tool Compatibility

| Tool | Status |
|---|---|
| Magisk | Full support — detected, boot script installed to `/data/adb/service.d/` |
| KernelSU | Full support — detected, boot script installed |
| LSPosed | Detected and shown in dashboard (GodMode works independently) |
| No root manager | Works if `su` binary is present |

---

## Troubleshooting

**"Daemon not starting"**
- Ensure root was granted in your root manager app
- Try: `adb shell su -c '/data/local/tmp/godmoded &'`
- Check logs: `adb logcat -s GodMode_Daemon`

**"Hook library not found"**
- The library must be at `/data/local/tmp/libgodmode_hook.so` or `/system/lib64/libgodmode_hook.so`
- Go to Settings → Reinstall Daemon in the app

**"Injection failing"**
- Android 16 may require `ptrace_scope` to be 0: `echo 0 > /proc/sys/kernel/yama/ptrace_scope`
- The daemon handles this automatically with root

**"App crashes after injection"**
- Some apps have anti-tamper detection; disable spoofing for that app
- Check logcat: `adb logcat -s GodMode_Hook GodMode_Binder`

---

## Security Notes

- GodMode requires root. It is designed for privacy research and personal device control.
- The daemon runs as root and has full system access.
- All data stays on-device — no network communication to external servers.
- The hook library is injected only into user-space app processes, not system_server.

---

## File Locations on Device

| File | Location |
|---|---|
| Daemon binary | `/data/local/tmp/godmoded` |
| Hook library | `/data/local/tmp/libgodmode_hook.so` |
| System hook (optional) | `/system/lib64/libgodmode_hook.so` |
| Config database | `/data/local/tmp/godmode.db` |
| PID file | `/data/local/tmp/godmoded.pid` |
| Boot script | `/data/adb/service.d/godmode.sh` |
| App database | `/data/data/com.godmode.app/databases/godmode_database` |
