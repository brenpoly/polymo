cmake_minimum_required(VERSION 3.22.1)
project(digitalpet-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ==============================================================================
# Compiler flags
# ==============================================================================
add_compile_options(-O3 -fvisibility=hidden -DGGML_NATIVE=OFF)
add_link_options("-Wl,-z,max-page-size=16384")

# ==============================================================================
# Path definitions
# ==============================================================================
set(DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../deps")
set(SUBMODULES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../")

set(LLAMA_CPP_DIR "${SUBMODULES_DIR}/llama.cpp")
set(WHISPER_CPP_DIR "${SUBMODULES_DIR}/whisper.cpp")
set(PIPER_DIR "${SUBMODULES_DIR}/piper")
set(OPUS_DIR "${DEPS_DIR}/opus")
set(ONNXRUNTIME_DIR "${DEPS_DIR}/onnxruntime")

# ==============================================================================
# llama.cpp — build from source
# ==============================================================================
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(SPIRV-Headers_DIR "/opt/homebrew/share/cmake/SPIRV-Headers")
set(Vulkan_GLSLC_EXECUTABLE "/opt/homebrew/bin/glslc")
list(APPEND CMAKE_PREFIX_PATH "/opt/homebrew")

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

if(TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE "/opt/homebrew/include")
endif()

# ==============================================================================
# whisper.cpp — build from source
# ==============================================================================
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

# ==============================================================================
# Opus — build from source with fixed-point math
# ==============================================================================
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
set(OPUS_DISABLE_FLOAT_API ON CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(${OPUS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/opus)

# ==============================================================================
# Piper TTS Integration
# ==============================================================================
set(PIPER_PHONEMIZE_DIR "${DEPS_DIR}/piper-phonemize")
set(ESPEAK_NG_SRC_DIR "${DEPS_DIR}/espeak-ng-src")

# Import prebuilt onnxruntime
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES 
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libonnxruntime.so"
)

# Import prebuilt espeak-ng
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES 
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libespeak-ng.so"
)

# ==============================================================================
# JNI bridge — the unified native library
# ==============================================================================
add_library(digitalpet-native SHARED
    llama-jni.cpp
    whisper-jni.cpp
    opus-jni.cpp
    piper-jni.cpp
    ${PIPER_DIR}/src/cpp/piper.cpp
    ${PIPER_PHONEMIZE_DIR}/src/phonemize.cpp
    ${PIPER_PHONEMIZE_DIR}/src/phoneme_ids.cpp
    ${PIPER_PHONEMIZE_DIR}/src/tashkeel.cpp
    ${PIPER_PHONEMIZE_DIR}/src/shared.cpp
)

target_include_directories(digitalpet-native PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/include
    ${OPUS_DIR}/include
    ${ONNXRUNTIME_DIR}/include
    ${ESPEAK_NG_SRC_DIR}/src/include
    ${PIPER_PHONEMIZE_DIR}/src
    ${PIPER_DIR}/src/cpp
    ${DEPS_DIR}/fmt/include
    ${DEPS_DIR}/spdlog/include
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(digitalpet-native PRIVATE
    llama
    whisper
    opus
    onnxruntime
    espeak-ng
    ${ANDROID_LOG_LIB}
)
