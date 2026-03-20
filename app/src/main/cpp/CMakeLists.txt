cmake_minimum_required(VERSION 3.22.1)
project("godmode")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# godmode_hook.so - Injected into target app processes
# This library intercepts Binder IPC calls and spoofs data
# ============================================================
add_library(
    godmode_hook
    SHARED
    hook/hook_main.cpp
    hook/binder_hook.cpp
    hook/property_hook.cpp
    hook/inline_hook.cpp
    ipc/socket_client.cpp
    spoof/spoof_engine.cpp
)

target_include_directories(godmode_hook PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/hook
    ${CMAKE_CURRENT_SOURCE_DIR}/ipc
    ${CMAKE_CURRENT_SOURCE_DIR}/spoof
    ${CMAKE_CURRENT_SOURCE_DIR}/daemon
)

target_link_libraries(godmode_hook
    android
    log
    dl
)

# ============================================================
# godmoded - Root daemon executable (standalone binary)
# Monitors process creation and manages injection
# ============================================================
add_executable(
    godmoded
    daemon/main.cpp
    daemon/process_monitor.cpp
    daemon/injector.cpp
    daemon/config_manager.cpp
    ipc/socket_server.cpp
    spoof/spoof_engine.cpp
)

target_include_directories(godmoded PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/daemon
    ${CMAKE_CURRENT_SOURCE_DIR}/ipc
    ${CMAKE_CURRENT_SOURCE_DIR}/spoof
    ${CMAKE_CURRENT_SOURCE_DIR}/hook
)

target_link_libraries(godmoded
    log
    dl
)

# ============================================================
# JNI Bridge - Called from Kotlin/Java
# ============================================================
add_library(
    godmode_jni
    SHARED
    daemon/jni_bridge.cpp
)

target_include_directories(godmode_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/daemon
    ${CMAKE_CURRENT_SOURCE_DIR}/ipc
)

target_link_libraries(godmode_jni
    android
    log
)
