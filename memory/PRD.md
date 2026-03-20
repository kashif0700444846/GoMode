# GodMode Android App - PRD

## Original Problem Statement
"Make this goMode app complete and give me apk file to install."

## Architecture
- **App Type**: Android native root application
- **Language**: Kotlin (UI) + C++ NDK (native hooks, daemon, JNI bridge)
- **Min SDK**: 29 (Android 10+)
- **Target SDK**: 34
- **ABI**: arm64-v8a only (ptrace injection requires 64-bit)
- **Build System**: Gradle 8.2 + AGP 8.2.2 + CMake 3.22.1 + NDK 25.1

## Core Features
- Device identifier spoofing (IMEI, Android ID, Serial, Advertising ID)
- Location spoofing (GPS coordinates)
- Network spoofing (MAC address, IP address)
- Build properties spoofing (Device model, brand, fingerprint)
- Telephony spoofing (Phone number, MCC/MNC)
- Per-app spoofing configuration (stored in Room database)
- Daemon with ptrace injection into target app processes
- Property hooks via `__system_property_get` interception
- Binder transaction hooks for system service calls
- Access log tracking with SQLite via Room

## What Was Implemented / Fixed
### 2026-03-20 - Build Fixes for CI/CD via GitHub Actions

All fixes were made iteratively by monitoring GitHub Actions CI logs:

1. **inline_hook.cpp** - Fixed `__builtin___clear_cache` type error: cast `void*` → `char*` for all 3 call sites
2. **themes.xml** - Added base `<style name="GodMode" />` required by AAPT2 for dot-notation child style resolution
3. **app/build.gradle** - Restricted ABI filters to `arm64-v8a` only (removed `armeabi-v7a` since `user_pt_regs` / ptrace injection is ARM64-specific)
4. **activity_app_detail.xml** + **activity_spoof_settings.xml** - Replaced `app:hint` with `android:hint` on all `TextInputLayout` elements (12 occurrences)
5. **DashboardFragment.kt** - Fixed ViewBinding references from `tvKernelSUStatus`→`tvKernelsuStatus` and `tvLSPosedStatus`→`tvLsposedStatus` to match generated camelCase from XML IDs

### Build Infrastructure
- GitHub Actions workflow: `.github/workflows/build.yml`
- NDK 26.1 installation + CMake 3.22.1 setup
- JDK 17 (Temurin) setup
- APK artifact upload (debug build)

## APK
- **File**: `GoMode-debug.apk` (16MB, debug build)
- **GitHub Actions Run #17**: https://github.com/kashif0700444846/GoMode/actions/runs/23357127033
- **Direct Download**: Actions → Run #17 → Artifacts → "GodMode-Debug-APK"

## Requirements (Static)
- Root access required (Magisk or KernelSU)
- Android 10+ (API 29+)
- 64-bit device (arm64-v8a)
- LSPosed or similar Xposed framework recommended for property hooks

## Prioritized Backlog
### P0 - Critical
- None (build passes, APK generated)

### P1 - Important
- [ ] Binder reply spoofing in `binder_hook.cpp` (marked TODO - actual Parcel parsing not implemented)
- [ ] Release signing / keystore setup for production APK
- [ ] ARM32 compatibility (either skip or add proper arm32 ptrace implementation)

### P2 - Nice to have
- [ ] Gradle version catalog migration
- [ ] Unit tests for spoof engine
- [ ] CI release workflow (signed APK)

## Next Tasks
1. Install APK on rooted Android device
2. Grant root permission when prompted
3. Test device identifier spoofing per-app
4. If Binder spoofing is needed, implement Parcel parsing in `binder_hook.cpp`
