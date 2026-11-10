cmake_minimum_required(VERSION 3.22)
project(openvpnclient_native CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(OPENVPN3_DIR "${PROJECT_ROOT}/third_party/openvpn3")
set(DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/deps/${ANDROID_ABI}")

if(NOT EXISTS "${OPENVPN3_DIR}/client/ovpncli.hpp")
    message(FATAL_ERROR "OpenVPN 3 Core submodule is missing. Run: git submodule update --init --recursive")
endif()
if(NOT EXISTS "${DEPS_DIR}/lib/libmbedtls.a")
    message(FATAL_ERROR "Native dependencies missing for ${ANDROID_ABI}. Run: scripts/build-native-deps.sh ${ANDROID_ABI}")
endif()

add_library(ovpn3 STATIC
    "${OPENVPN3_DIR}/client/ovpncli.cpp"
)
target_include_directories(ovpn3 PUBLIC
    "${OPENVPN3_DIR}"
    "${OPENVPN3_DIR}/client"
    "${DEPS_DIR}/include"
)
target_compile_definitions(ovpn3 PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    HAVE_LZ4
    USE_MBEDTLS
    FMT_HEADER_ONLY
    OPENVPN_CORE_API_VISIBILITY_HIDDEN
)
target_compile_options(ovpn3 PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-deprecated-declarations
    -fexceptions
    -frtti
)

add_library(nativevpn SHARED
    native_bridge.cpp
    engine_adapter/ovpn_client.cpp
)
target_include_directories(nativevpn PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/engine_adapter"
)
target_compile_definitions(nativevpn PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    FMT_HEADER_ONLY
)
target_compile_options(nativevpn PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fexceptions
    -frtti
)
target_link_libraries(nativevpn PRIVATE
    ovpn3
    "${DEPS_DIR}/lib/libjsoncpp.a"
    "${DEPS_DIR}/lib/libmbedtls.a"
    "${DEPS_DIR}/lib/libmbedx509.a"
    "${DEPS_DIR}/lib/libmbedcrypto.a"
    "${DEPS_DIR}/lib/liblz4.a"
    android
    log
)
target_link_options(nativevpn PRIVATE
    "-Wl,--exclude-libs,ALL"
)
