# GoMode - Product Requirements Document

## Original Problem Statement
Build a comprehensive, all-in-one Android root utility called "GoMode" that combines the core functionalities of:
- **KernelSU** (kernel-based root + module management)
- **LSPosed** (ART hooking framework)
- **XPL-EX** (privacy manager / property spoofing)
- **NeoZygisk** (Zygote injection)

Into a single user-friendly application for rooted Android devices.

## User Personas
- Power users with rooted Android devices (Magisk / KernelSU)
- Privacy-conscious users who want per-app identity spoofing
- Developers needing a terminal with root access
- Users managing Xposed / Zygisk modules

## Core Requirements

### Stability
- App must NOT crash on first launch (even if root is not available)
- All native calls must have Kotlin fallbacks
- Graceful degradation when features are unavailable

### Root Management
- Detect Magisk / KernelSU / LSPosed / Zygisk status
- Request and verify root access
- Execute root shell commands (via native JNI + Kotlin fallback)

### Module Management (Modules Tab)
- List all installed modules from /data/adb/modules/
- Enable / Disable / Delete modules
- KernelSU tab: kernel module management + superuser grants
- LSPosed tab: Xposed framework status + module list
- Zygisk tab: NeoZygisk status + companion process list
- XPL-EX tab: Privacy engine overview + live device identifier read

### Privacy Engine (XPL-EX inspired)
- Per-app property spoofing (IMEI, Android ID, Serial, GPS, MAC)
- Configurable via the Apps tab
- Access logging per app

### Terminal
- Root shell with command history
- cd navigation
- Clear button + scrollable output

### Power Menu
- Reboot, Power Off
- Reboot to Recovery, Bootloader, EDL
- Safe Mode reboot

### Setup Wizard
- First-launch setup that installs the GoMode daemon
- Graceful error handling - never crashes even if root is unavailable

## Architecture

```
/app
├── .github/workflows/build.yml         # GitHub Actions CI/CD
├── app/
│   ├── build.gradle                    # arm64-v8a ABI restricted
│   └── src/main/
│       ├── cpp/                        # Native C++ (JNI, ptrace, hooking)
│       ├── java/com/godmode/app/
│       │   ├── GodModeApp.kt           # Application class
│       │   ├── daemon/
│       │   │   └── RootManager.kt      # All root ops + module management
│       │   ├── data/                   # Room DB, models, repository
│       │   ├── service/                # Background services
│       │   └── ui/
│       │       ├── setup/SetupWizardActivity.kt
│       │       ├── dashboard/DashboardFragment.kt
│       │       ├── apps/AppsFragment.kt
│       │       ├── modules/ModulesFragment.kt  # NEW: Module manager
│       │       ├── terminal/TerminalFragment.kt
│       │       ├── logs/LogsFragment.kt
│       │       └── settings/SettingsFragment.kt
│       └── res/
│           ├── layout/
│           │   ├── fragment_modules.xml   # NEW
│           │   └── item_module.xml        # NEW
│           ├── navigation/nav_graph.xml   # Updated with modulesFragment
│           └── menu/bottom_nav_menu.xml   # Dashboard/Apps/Modules/Terminal/Settings
```

## What's Been Implemented

### v1 (Initial Build)
- Fixed all native C++ compilation errors (ARMv7/ARM64, builtins, cast issues)
- Fixed AAPT2 resource errors (styles, color duplicates, app:hint → android:hint)
- Established GitHub Actions CI/CD pipeline

### v2 (Feature Overhaul)
- Renamed app from "GodMode" to "GoMode"
- Fixed app list UI (item_app.xml visibility)
- Added Setup Wizard (SetupWizardActivity)
- Added Terminal UI (TerminalFragment)
- Added Power Menu in Settings
- Updated AndroidManifest with all system permissions

### v3 (Crash Fix + Module Manager) - 2026-03-20
- **FIXED P0 CRASH**: Changed catch(Exception) → catch(Throwable) across all native calls
- Added `execRootCommand()` safe wrapper with Kotlin fallback (no JNI required)
- Added `nativeLibLoaded` flag to prevent calling unloaded native methods
- SetupWizardActivity: entire install coroutine wrapped in try-catch
- Repository, DaemonService, SpoofSettingsActivity all migrated to safe wrappers
- **NEW: ModulesFragment** with 4-tab UI (KernelSU/LSPosed/Zygisk/XPL-EX)
  - Module list from /data/adb/modules/ with enable/disable/delete
  - Framework version detection (Magisk/KernelSU/LSPosed/Zygisk)
  - Superuser grants via Magisk/KernelSU sqlite
  - NeoZygisk companion process management
  - XPL-EX privacy engine overview + live device identifiers
- Navigation: Modules tab replaces Logs in bottom nav (5 tabs)
- APK Build #18: SUCCESS (6.1 MB)

## Build Info
- CI/CD: GitHub Actions → https://github.com/kashif0700444846/GoMode/actions
- Latest successful APK: Build #18 (run 23360790026)
- Download: https://github.com/kashif0700444846/GoMode/actions/runs/23360790026

## DB Schema
- **app_configs**: Per-app spoofing configs (packageName PK)
- **access_logs**: Property access logs per app

## Prioritized Backlog

### P0 (Critical)
- None currently

### P1 (High Priority)
- Deep KernelSU integration: Read and manage superuser grants UI list
- LSPosed scope editor: Per-module per-app hook enable/disable
- Terminal: Multi-line input, better output formatting, ANSI color support

### P2 (Medium Priority)
- Magisk module flasher: Install .zip modules directly from GoMode
- Xposed module installer: Download and install from common repos
- Privacy profiles: Save/restore full privacy configurations
- App log viewer improvements

### Future/Backlog
- Magisk module builder for GoMode persistence
- KernelSU native support (ksud integration)
- SELinux policy manager
- Hosts file editor with ad-blocking profiles
- Build.prop editor with safety backup
