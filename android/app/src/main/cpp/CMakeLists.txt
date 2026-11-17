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

# OpenVPN Core 3.9 advertises dynamic tls-crypt (IV_PROTO_DYN_TLS_CRYPT)
# whenever SHA-256 and AES-256-CTR are available, without checking whether the
# TLS backend can export keying material. With USE_MBEDTLS the backend cannot
# (openvpn/mbedtls/ssl/sslctx.hpp returns false), so a server may enable
# dynamic tls-crypt and the first rekey aborts with KEY_EXPANSION_ERROR
# ("TLS Keying material export error"), which Core turns into an unhandled
# exception. Generate a patched copy of the pinned header so the capability is
# only advertised when it can be completed, and so a server that pushes the flag
# anyway fails with a clear server-push error instead of the delayed rekey
# crash. The submodule commit stays pinned.
set(OPENVPN3_PATCHED_DIR "${CMAKE_CURRENT_BINARY_DIR}/openvpn3-patched")
set(OPENVPN3_PROTO_SOURCE "${OPENVPN3_DIR}/openvpn/ssl/proto.hpp")
set(OPENVPN3_PROTO_PATCHED "${OPENVPN3_PATCHED_DIR}/openvpn/ssl/proto.hpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${OPENVPN3_PROTO_SOURCE}")
file(READ "${OPENVPN3_PROTO_SOURCE}" OPENVPN3_PROTO_CONTENT)
# Normalise line endings from a Windows checkout before matching the patch text.
string(REPLACE "\r\n" "\n" OPENVPN3_PROTO_CONTENT "${OPENVPN3_PROTO_CONTENT}")
string(REPLACE "\r" "\n" OPENVPN3_PROTO_CONTENT "${OPENVPN3_PROTO_CONTENT}")

# 1. Only advertise dynamic tls-crypt when the TLS backend can complete it.
set(OPENVPN3_DYN_TLS_CRYPT_UNPATCHED
    "            if (CryptoAlgs::lookup(\"SHA256\") != CryptoAlgs::NONE && CryptoAlgs::lookup(\"AES-256-CTR\") != CryptoAlgs::NONE)\n                iv_proto |= IV_PROTO_DYN_TLS_CRYPT;")
set(OPENVPN3_DYN_TLS_CRYPT_PATCHED
    "            if (SSLLib::SSLAPI::support_key_material_export()\n                && CryptoAlgs::lookup(\"SHA256\") != CryptoAlgs::NONE\n                && CryptoAlgs::lookup(\"AES-256-CTR\") != CryptoAlgs::NONE)\n                iv_proto |= IV_PROTO_DYN_TLS_CRYPT;")
string(FIND "${OPENVPN3_PROTO_CONTENT}" "${OPENVPN3_DYN_TLS_CRYPT_UNPATCHED}" OPENVPN3_DYN_TLS_CRYPT_POS)
if(OPENVPN3_DYN_TLS_CRYPT_POS EQUAL -1)
    message(FATAL_ERROR
        "The OpenVPN Core 3.9 dynamic tls-crypt compatibility patch no longer applies to "
        "${OPENVPN3_PROTO_SOURCE}. Re-check peer_info_string() before updating the pinned core.")
endif()
string(REPLACE
    "${OPENVPN3_DYN_TLS_CRYPT_UNPATCHED}"
    "${OPENVPN3_DYN_TLS_CRYPT_PATCHED}"
    OPENVPN3_PROTO_CONTENT
    "${OPENVPN3_PROTO_CONTENT}")

# 2. Reject a server-pushed dyn-tls-crypt instead of failing at the first rekey.
set(OPENVPN3_DYN_TLS_CRYPT_PUSH_UNPATCHED
    "                        else if (flag == \"dyn-tls-crypt\")\n                        {\n                            set_tls_crypt_algs();\n                            tls_crypt_ |= Dynamic;\n                        }")
set(OPENVPN3_DYN_TLS_CRYPT_PUSH_PATCHED
    "                        else if (flag == \"dyn-tls-crypt\")\n                        {\n                            if (!SSLLib::SSLAPI::support_key_material_export())\n                                OPENVPN_THROW(process_server_push_error,\n                                              \"server requires dynamic tls-crypt but this TLS backend cannot export keying material\");\n                            set_tls_crypt_algs();\n                            tls_crypt_ |= Dynamic;\n                        }")
string(FIND "${OPENVPN3_PROTO_CONTENT}" "${OPENVPN3_DYN_TLS_CRYPT_PUSH_UNPATCHED}" OPENVPN3_DYN_TLS_CRYPT_PUSH_POS)
if(OPENVPN3_DYN_TLS_CRYPT_PUSH_POS EQUAL -1)
    message(FATAL_ERROR
        "The OpenVPN Core 3.9 dynamic tls-crypt push guard no longer applies to "
        "${OPENVPN3_PROTO_SOURCE}. Re-check parse_pushed_protocol_flags() before updating the pinned core.")
endif()
string(REPLACE
    "${OPENVPN3_DYN_TLS_CRYPT_PUSH_UNPATCHED}"
    "${OPENVPN3_DYN_TLS_CRYPT_PUSH_PATCHED}"
    OPENVPN3_PROTO_CONTENT
    "${OPENVPN3_PROTO_CONTENT}")

# 3. Strict security policy (issue #19): when the app disabled compression,
#    refuse a server-pushed real compressor instead of silently degrading to
#    asymmetric compression. Compression stubs carry no compressed payload and
#    stay accepted, so providers that push a stub keep working in strict mode.
set(OPENVPN3_STRICT_COMPRESSION_UNPATCHED
    "                        if (pco.is_comp())\n                            comp_ctx = CompressContext(meth, pco.is_comp_asym());\n                        else\n                        {\n                            // server pushes compression but client has compression disabled\n                            // degrade to asymmetric compression (downlink only)\n                            comp_ctx = CompressContext(meth, true);\n                            if (!comp_ctx.is_any_stub(meth))\n                            {\n                                OPENVPN_LOG(\"Server has pushed compressor \"\n                                            << comp_ctx.str()\n                                            << \", but client has disabled compression, switching to asymmetric\");\n                            }\n                        }")
set(OPENVPN3_STRICT_COMPRESSION_PATCHED
    "                        if (pco.is_comp())\n                            comp_ctx = CompressContext(meth, pco.is_comp_asym());\n                        else if (CompressContext::is_any_stub(meth))\n                        {\n                            // A compression stub carries no compressed payload; keep\n                            // accepting it so stub-pushing providers still work.\n                            comp_ctx = CompressContext(meth, true);\n                        }\n                        else\n                        {\n                            // The app's strict security policy disabled compression and\n                            // must not silently degrade to asymmetric compression.\n                            OPENVPN_THROW(process_server_push_error,\n                                          \"server requires compression but the app's strict security policy refuses it\");\n                        }")
string(FIND "${OPENVPN3_PROTO_CONTENT}" "${OPENVPN3_STRICT_COMPRESSION_UNPATCHED}" OPENVPN3_STRICT_COMPRESSION_POS)
if(OPENVPN3_STRICT_COMPRESSION_POS EQUAL -1)
    message(FATAL_ERROR
        "The OpenVPN Core 3.9 strict-compression guard no longer applies to "
        "${OPENVPN3_PROTO_SOURCE}. Re-check parse_pushed_compression() before updating the pinned core.")
endif()
string(REPLACE
    "${OPENVPN3_STRICT_COMPRESSION_UNPATCHED}"
    "${OPENVPN3_STRICT_COMPRESSION_PATCHED}"
    OPENVPN3_PROTO_CONTENT
    "${OPENVPN3_PROTO_CONTENT}")

# Only rewrite the generated header when its content changed: an unconditional
# file(WRITE) on every configure bumps its timestamp and rebuilds the whole
# OpenVPN Core translation unit for a no-op Gradle sync.
set(OPENVPN3_PROTO_WRITE TRUE)
if(EXISTS "${OPENVPN3_PROTO_PATCHED}")
    file(READ "${OPENVPN3_PROTO_PATCHED}" OPENVPN3_PROTO_EXISTING)
    if("${OPENVPN3_PROTO_EXISTING}" STREQUAL "${OPENVPN3_PROTO_CONTENT}")
        set(OPENVPN3_PROTO_WRITE FALSE)
    endif()
endif()
if(OPENVPN3_PROTO_WRITE)
    file(MAKE_DIRECTORY "${OPENVPN3_PATCHED_DIR}/openvpn/ssl")
    file(WRITE "${OPENVPN3_PROTO_PATCHED}" "${OPENVPN3_PROTO_CONTENT}")
endif()

add_library(ovpn3 STATIC
    "${OPENVPN3_DIR}/client/ovpncli.cpp"
)
target_include_directories(ovpn3 PUBLIC
    "${OPENVPN3_PATCHED_DIR}"
    "${OPENVPN3_DIR}"
    "${OPENVPN3_DIR}/client"
    "${DEPS_DIR}/include"
)
target_compile_definitions(ovpn3 PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    HAVE_LZ4
    USE_MBEDTLS
    MBEDTLS_SELF_TEST
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
    # Native hardening (issue #12). The app builds with -O2 from build.gradle.kts,
    # so FORTIFY checks are active. Stack canaries cover Core's packet/TLS paths.
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
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
    # Native hardening (issue #12). The app builds with -O2 from build.gradle.kts,
    # so FORTIFY checks are active. Stack canaries cover the JNI/TUN parser paths.
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
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
    # Full RELRO plus non-lazy binding (issue #12): the GOT is read-only after
    # relocation and no symbol is resolved lazily at call time.
    "-Wl,-z,relro"
    "-Wl,-z,now"
    # Android 15+ devices can use 16 KB memory pages. Align the shared object
    # so the packaged native library loads on those devices.
    "-Wl,-z,max-page-size=16384"
)
