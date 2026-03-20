# GoMode - Product Requirements Document

## Original Problem Statement
Build a comprehensive, all-in-one Android root utility called "GoMode" combining:
- **KernelSU** (kernel-based root + module management)
- **LSPosed** (ART hooking framework + Xposed modules)
- **XPL-EX** (privacy manager / hardware ID spoofing)
- **NeoZygisk** (Zygote injection)

Into a single user-friendly application. The app should:
- Work 100% - spoofing must actually intercept app API calls
- Track ALL app permission accesses with logs
- Manage ALL installed apps (not just configured ones)
- Control WiFi, mobile data, hotspot, all system settings
- Provide network traffic inspection (ProxyPin-like)

## User Personas
- Power users with rooted Android devices (Magisk / KernelSU)
- Privacy-conscious users wanting per-app identity spoofing
- Developers needing root terminal access
- Users managing Xposed / Zygisk modules

## Architecture

```
/app
├── .github/workflows/build.yml              # GitHub Actions CI/CD
└── app/src/main/
    ├── assets/xposed_init                   # Xposed module entry point
    ├── cpp/                                 # Native C++ (JNI, ptrace, hooking)
    ├── java/com/godmode/app/
    │   ├── xposed/GoModeXposedModule.kt     # LSPosed hooks (100% spoofing)
    │   ├── daemon/RootManager.kt            # All root ops + app management
    │   ├── data/                            # Room DB, models, repository
    │   ├── service/                         # Background services
    │   └── ui/
    │       ├── setup/SetupWizardActivity.kt
    │       ├── dashboard/DashboardFragment.kt
    │       ├── apps/AppsFragment.kt + AppDetailActivity.kt
    │       ├── modules/ModulesFragment.kt   # KernelSU/LSPosed/Zygisk/XPL-EX
    │       ├── network/NetworkFragment.kt   # WiFi/network/system settings
    │       └── settings/SettingsFragment.kt  # Power menu + terminal access
    └── res/
        ├── navigation/nav_graph.xml         # Dashboard/Apps/Modules/Network/Settings
        └── menu/bottom_nav_menu.xml         # 5-tab bottom nav
```

## Implemented Features

### v5 (2026-03-20) - P0 Stability Pass (Install Hang + Spoofing + Logs)
- Reworked root command execution to timeout-safe Kotlin process handling (prevents indefinite `su` hangs).
- Added startup/setup timeouts in `MainActivity` and `SetupWizardActivity` to avoid getting stuck on install screens.
- Hardened daemon lifecycle checks (`socket + pidof`) and startup routine (`nohup`, runtime logs, install verification).
- Added shared root-readable config pipeline for Xposed (`/data/local/tmp/gomode/config/<pkg>.conf`) so spoof settings still load if `XSharedPreferences` path is inaccessible.
- Expanded config export from `AppDetailActivity` (IMEI/IMSI/build/location/ad id keys) and immediate config reload signaling.
- Added Xposed log pipeline to root-readable path (`/data/local/tmp/gomode/logs/access.jsonl`) with structured JSON entries.
- Implemented ingestion of Xposed logs into Room via `GodModeDaemonService`, with timestamp checkpointing.
- Improved IMEI read fallbacks (`cmd phone`, `service call iphonesubinfo`, `dumpsys`, `getprop`).

### v3 (2026-03-20) - Crash Fix + Module Manager
- Fixed P0 startup crash (Throwable handling)
- Module Manager with KernelSU/LSPosed/Zygisk/XPL-EX tabs
- Better root command execution with Kotlin fallback

### v4 (2026-03-20) - Full Privacy Engine + App Management
**XPOSED MODULE (100% spoofing via LSPosed):**
- `GoModeXposedModule.kt` hooks into every app process
- IMEI/MEID/DeviceId interception (3 methods + dual SIM)
- IMSI/subscriber ID spoofing
- Android ID spoofing via Settings.Secure hook
- Build.SERIAL, Build.MODEL, Build.BRAND, Build.FINGERPRINT fields
- WiFi MAC (WifiInfo + java.net.NetworkInterface)
- Widevine DRM deviceUniqueId spoofing
- GPS/Location spoofing (getLastKnownLocation)
- Camera blocking (CameraManager + legacy Camera API)
- Microphone blocking (AudioRecord + MediaRecorder)
- Google Play Advertising ID spoofing
- Access logging: JSON written to xposed_logs/access.jsonl
- Config via XSharedPreferences (LSPosed bridges cross-process access)
- `AndroidManifest.xml` has xposedmodule/description/minversion metadata
- `assets/xposed_init` registers the hook class

**APP MANAGEMENT:**
- Force Stop app (am force-stop)
- Clear Cache (rm -rf cache dirs)
- Clear Data (pm clear)
- Open App
- View Permissions + App Ops (pm dump + appops get)
- pm grant/revoke applied immediately when blocking camera/mic
- AppDetailActivity.saveConfig() writes XSharedPreferences for Xposed module

**NETWORK & SYSTEM SETTINGS TAB:**
- WiFi/Mobile Data/Hotspot/Bluetooth/Airplane Mode toggles
- WiFi network scanner (wpa_cli)
- DNS configurator (setprop net.dns1/dns2)
- /etc/hosts editor (ad-blocking, bypass)
- iptables firewall viewer
- Active connections (ss -tunap)
- Traffic stats (/proc/net/xt_qtaguid)
- resetprop/setprop system property editor

**BUG FIXES:**
- MainActivity no longer blocks UI with daemon install dialog
- Root tool detection uses execRootCommand() (KernelSU/Magisk/LSPosed now detected)
- IMEI reading via 3 fallback methods
- Dashboard shows actual version strings (green=detected, grey=not found)
- Banner guides user to enable GoMode in LSPosed Manager

## Build Info
- Latest APK: Build #20 (Run 23364257718)
- Download: https://github.com/kashif0700444846/GoMode/actions/runs/23364257718
- APK Size: ~6.2 MB

## Prioritized Backlog

### P0 (Critical - user to test)
- Verify v5 setup no longer hangs on "Installing daemon" (fresh install + after reboot)
- Verify IMEI spoof is applied in at least one target app scope (KernelSU + LSPosed)
- Verify access logs appear in Logs tab after target app reads IMEI/Android ID/location
- Confirm fallback config path works (`/data/local/tmp/gomode/config/*.conf`)

### P1 (Next Implementation)
- Network traffic inspector (ProxyPin-like): intercept HTTP/HTTPS
  - Option A: VpnService-based packet capture (no extra binaries)
  - Option B: iptables TPROXY + mitmproxy approach
- LSPosed scope editor: per-module per-app hook enable/disable
- Show ALL installed apps in Apps tab (not just configured ones)
- App icon display with logs (show which app icon alongside access log)

### P2 (Medium Priority)
- Magisk/KernelSU module flasher (install .zip modules directly)
- App-to-app communication tracking (Binder IPC logging)
- Privacy profiles: save/load full identity sets
- APK installer/manager

### Future/Backlog
- Full ProxyPin-equivalent: SSL interception + request modification UI
- SELinux policy manager
- Build.prop editor with live apply
- Hosts file with built-in ad-block lists
- KernelSU superuser grants full management
