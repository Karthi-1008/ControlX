cmake_minimum_required(VERSION 3.12)
project(native-mgba C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# High performance optimization flags for ARM Cortex CPU
add_compile_options(-O3 -fomit-frame-pointer -ffast-math)

# Options for mGBA libretro build
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(LIBRETRO_STATIC ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(USE_EPOXY OFF CACHE BOOL "" FORCE)
set(USE_PNG OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "" FORCE)
set(USE_SQLITE3 OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)

# Locate mGBA source directory
set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../mgba")
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mgba")
endif()
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(STATUS "mGBA submodule not found, cloning shallowly...")
    find_package(Git REQUIRED)
    execute_process(
        COMMAND ${GIT_EXECUTABLE} clone --depth 1 https://github.com/libretro/mgba.git ${MGBA_DIR}
        RESULT_VARIABLE GIT_CLONE_RES
    )
    if(NOT GIT_CLONE_RES EQUAL 0)
        message(FATAL_ERROR "Failed to clone mGBA repository")
    endif()
endif()

add_subdirectory(${MGBA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/mgba_build EXCLUDE_FROM_ALL)

# Build JNI shared library
add_library(mgba-jni SHARED
    mgba_jni.c
)

target_include_directories(mgba-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${MGBA_DIR}/include
    ${MGBA_DIR}/src
)

target_link_libraries(mgba-jni PRIVATE
    mgba_libretro
    android
    log
)

# Build NES JNI shared library
add_library(nes-jni SHARED
    nes_jni.cpp
    nes_core.cpp
)

target_include_directories(nes-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(nes-jni PRIVATE
    android
    log
)

