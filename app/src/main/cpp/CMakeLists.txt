cmake_minimum_required(VERSION 3.22.1)

project(phonellama_asr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 8086439a4cea94c71a5dfb8fe4ad1546aebd640f
)
FetchContent_MakeAvailable(llama)

# Fetch only the pinned FunASR source tree. Its desktop CMake project is not
# added as a subproject because Android needs a shared JNI library, not a CLI.
FetchContent_Declare(
    funasr
    GIT_REPOSITORY https://github.com/QwenAudio/SenseVoice.git
    GIT_TAG runtime-llamacpp-v0.1.9
)
FetchContent_GetProperties(funasr)
if(NOT funasr_POPULATED)
    FetchContent_Populate(funasr)
endif()

set(FUNASR_ROOT "${funasr_SOURCE_DIR}/runtime/llama.cpp")
set(FUNASR_COMMON "${FUNASR_ROOT}/funasr-common")
set(FUNASR_SENSEVOICE "${FUNASR_ROOT}/funasr-sensevoice/funasr-sensevoice.cpp")

# The upstream implementation is a self-contained CLI. Renaming main lets
# the JNI bridge invoke the same validated inference path in-process.
add_library(funasr_sensevoice OBJECT "${FUNASR_SENSEVOICE}")
target_compile_definitions(funasr_sensevoice PRIVATE main=phonellama_funasr_main)
target_include_directories(
    funasr_sensevoice
    PRIVATE
    "${FUNASR_COMMON}"
    "${llama_SOURCE_DIR}/ggml/include"
    "${llama_SOURCE_DIR}/ggml/src"
)
target_compile_features(funasr_sensevoice PRIVATE cxx_std_17)

add_library(
    phonellama_asr
    SHARED
    sensevoice_jni.cpp
    $<TARGET_OBJECTS:funasr_sensevoice>
)
target_include_directories(
    phonellama_asr
    PRIVATE
    "${llama_SOURCE_DIR}/ggml/include"
    "${llama_SOURCE_DIR}/ggml/src"
)
target_link_libraries(phonellama_asr PRIVATE ggml log android)
target_compile_features(phonellama_asr PRIVATE cxx_std_17)
