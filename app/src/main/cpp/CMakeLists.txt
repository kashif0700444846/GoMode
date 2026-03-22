cmake_minimum_required(VERSION 3.22.1)
project("godmode")

# CRITICAL FIX: Output .so files directly to jniLibs folder
# This ensures Gradle packages them into the APK
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../assets)

message(STATUS "CMake output dir for libs: ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
message(STATUS "CMake output dir for executables: ${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")

# Build godmode_hook shared library
add_library(godmode_hook SHARED
    hook/binder_hook.cpp
    hook/inline_hook.cpp
    hook/property_hook.cpp
    spoof/spoof_engine.cpp
    ipc/socket_client.cpp
)

target_link_libraries(godmode_hook
    android
    log
)

# Build godmode_jni shared library
add_library(godmode_jni SHARED
    daemon/jni_bridge.cpp
)

target_link_libraries(godmode_jni
    android
    log
)

# Build godmoded executable  
add_executable(godmoded
    daemon/main.cpp
    daemon/daemon_core.cpp
    daemon/root_manager.cpp
    daemon/config_manager.cpp
    daemon/process_monitor.cpp
    ipc/socket_server.cpp
    spoof/spoof_engine.cpp
    hook/binder_hook.cpp
    hook/inline_hook.cpp
    hook/property_hook.cpp
)

target_link_libraries(godmoded
    android
    log
)
