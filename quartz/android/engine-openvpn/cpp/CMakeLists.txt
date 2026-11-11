# libovpn3.so: the openvpn3 core (quartz/android/engines/openvpn3, submodule, unpatched) with
# Quartz's JNI wrapper (ovpn_jni.cpp). Built per ABI by quartz.gradle with the NDK's toolchain
# file; also builds on the host for a compile check (FORK.md §3, OpenVPN engine).
#
# openvpn3's own dependencies are fetched at configure time, pinned: asio (with openvpn3's
# patches from its deps/asio/patches), lz4, mbed TLS and xxHash. Their downloads are cached by
# CMake in the build directory, which quartz.gradle keeps between builds.
cmake_minimum_required(VERSION 3.22)
project(quartz_ovpn3 C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE MinSizeRel)
endif ()

set(OPENVPN3_DIR "${CMAKE_CURRENT_LIST_DIR}/../../engines/openvpn3" CACHE PATH "openvpn3 source")
if (NOT EXISTS "${OPENVPN3_DIR}/client/ovpncli.cpp")
    message(FATAL_ERROR "openvpn3 not found at ${OPENVPN3_DIR}: git submodule update --init quartz/android/engines/openvpn3")
endif ()

include(FetchContent)
set(FETCHCONTENT_QUIET ON)

# asio: header-only, patched like openvpn3's own builds (deps/asio/build-asio).
file(GLOB ASIO_PATCHES "${OPENVPN3_DIR}/deps/asio/patches/*.patch")
list(SORT ASIO_PATCHES)
set(ASIO_PATCH_COMMAND)
foreach (p ${ASIO_PATCHES})
    list(APPEND ASIO_PATCH_COMMAND COMMAND patch -p1 -N -i "${p}")
endforeach ()
FetchContent_Declare(asio
    GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
    GIT_TAG asio-1-24-0
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${CMAKE_COMMAND} -E true ${ASIO_PATCH_COMMAND}
    UPDATE_DISCONNECTED TRUE)

FetchContent_Declare(lz4
    GIT_REPOSITORY https://github.com/lz4/lz4.git
    GIT_TAG v1.10.0
    GIT_SHALLOW TRUE)

FetchContent_Declare(xxhash
    GIT_REPOSITORY https://github.com/Cyan4973/xxHash.git
    GIT_TAG v0.8.3
    GIT_SHALLOW TRUE)

# The release tarball, not the git tag: it carries the generated files and the framework.
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.4/mbedtls-3.6.4.tar.bz2
    URL_HASH SHA256=ec35b18a6c593cf98c3e30db8b98ff93e8940a8c4e690e66b41dfc011d678110
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

FetchContent_GetProperties(asio)
if (NOT asio_POPULATED)
    FetchContent_Populate(asio)
endif ()
FetchContent_GetProperties(lz4)
if (NOT lz4_POPULATED)
    FetchContent_Populate(lz4)
endif ()
FetchContent_GetProperties(xxhash)
if (NOT xxhash_POPULATED)
    FetchContent_Populate(xxhash)
endif ()

set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mbedtls)

add_library(ovpn3 SHARED
    "${CMAKE_CURRENT_LIST_DIR}/ovpn_jni.cpp"
    "${OPENVPN3_DIR}/client/ovpncli.cpp"
    "${OPENVPN3_DIR}/openvpn/crypto/data_epoch.cpp"
    "${lz4_SOURCE_DIR}/lib/lz4.c"
    "${xxhash_SOURCE_DIR}/xxhash.c")

target_include_directories(ovpn3 PRIVATE
    "${OPENVPN3_DIR}"
    "${asio_SOURCE_DIR}/asio/include"
    "${lz4_SOURCE_DIR}/lib"
    "${xxhash_SOURCE_DIR}")

# Same core defines as openvpn3's findcoredeps.cmake, with mbed TLS as the crypto library.
target_compile_definitions(ovpn3 PRIVATE
    ASIO_STANDALONE
    ASIO_NO_DEPRECATED
    USE_ASIO
    HAVE_LZ4
    USE_MBEDTLS
    OPENVPN_CORE_API_VISIBILITY_HIDDEN)

if (NOT ANDROID)
    # Host compile check only: the Android code path (tun builder) without an Android toolchain.
    find_package(JNI REQUIRED)
    target_include_directories(ovpn3 PRIVATE ${JNI_INCLUDE_DIRS})
    target_compile_definitions(ovpn3 PRIVATE USE_TUN_BUILDER)
endif ()

target_compile_options(ovpn3 PRIVATE -Wno-deprecated-declarations -fvisibility=hidden)
target_link_libraries(ovpn3 PRIVATE mbedtls mbedx509 mbedcrypto)
if (ANDROID)
    target_link_libraries(ovpn3 PRIVATE log)
    # 16 KB page-size devices (Android 15+).
    target_link_options(ovpn3 PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s)
else ()
    find_package(Threads REQUIRED)
    target_link_libraries(ovpn3 PRIVATE Threads::Threads)
endif ()
