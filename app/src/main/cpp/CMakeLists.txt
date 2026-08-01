cmake_minimum_required(VERSION 3.22.1)

project(phonellama_asr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)

if(ANDROID)
    set(ANDROID_VULKAN_SYSROOT
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
    set(Vulkan_INCLUDE_DIR
        "${ANDROID_VULKAN_SYSROOT}/usr/include" CACHE PATH "" FORCE)
    set(Vulkan_LIBRARY
        "${ANDROID_VULKAN_SYSROOT}/usr/lib/aarch64-linux-android/${CMAKE_SYSTEM_VERSION}/libvulkan.so"
        CACHE FILEPATH "" FORCE)
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)

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

# Patch the upstream CLI at configure time so its graph can use the selected
# llama.cpp backend while keeping the upstream model and audio implementation.
set(FUNASR_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/funasr-sensevoice.cpp")
file(READ "${FUNASR_SENSEVOICE}" FUNASR_SOURCE)
string(REPLACE
    "#include \"ggml-cpu.h\""
    "#include \"ggml-cpu.h\"\n#include \"ggml-vulkan.h\""
    FUNASR_SOURCE "${FUNASR_SOURCE}")
string(REPLACE
    "int main(int argc,char**argv){"
    "int main(int argc,char**argv){ bool use_gpu=false;"
    FUNASR_SOURCE "${FUNASR_SOURCE}")
string(REPLACE
    "else if(!strcmp(argv[i],\"--keep-tags\"))keep_tags=true;"
    "else if(!strcmp(argv[i],\"--keep-tags\"))keep_tags=true;\n else if(!strcmp(argv[i],\"--backend\")&&i+1<argc)use_gpu=!strcmp(argv[++i],\"gpu\");"
    FUNASR_SOURCE "${FUNASR_SOURCE}")
string(REPLACE
    "ggml_backend_t be=ggml_backend_cpu_init();"
    "ggml_backend_t be=use_gpu && ggml_backend_vk_get_device_count()>0 ? ggml_backend_vk_init(0) : ggml_backend_cpu_init();"
    FUNASR_SOURCE "${FUNASR_SOURCE}")
string(REPLACE
    "ggml_gallocr_t ga=ggml_gallocr_new(ggml_backend_cpu_buffer_type());"
    "ggml_gallocr_t ga=ggml_gallocr_new(use_gpu && ggml_backend_vk_get_device_count()>0 ? ggml_backend_vk_buffer_type(0) : ggml_backend_cpu_buffer_type());"
    FUNASR_SOURCE "${FUNASR_SOURCE}")
string(REPLACE
    "ggml_backend_cpu_set_n_threads(be,8);"
    "if(!use_gpu)ggml_backend_cpu_set_n_threads(be,8);"
    FUNASR_SOURCE "${FUNASR_SOURCE}")
file(WRITE "${FUNASR_PATCHED}" "${FUNASR_SOURCE}")

# The patched implementation remains a self-contained CLI. Renaming main lets
# the JNI bridge invoke the validated inference path in-process.
add_library(funasr_sensevoice OBJECT "${FUNASR_PATCHED}")
target_compile_definitions(funasr_sensevoice PRIVATE main=phonellama_funasr_main)
target_include_directories(
    funasr_sensevoice
    PRIVATE
    "${FUNASR_COMMON}"
    "${FUNASR_ROOT}/funasr-sensevoice"
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
target_link_libraries(phonellama_asr PRIVATE ggml ggml-vulkan log android vulkan)
target_compile_features(phonellama_asr PRIVATE cxx_std_17)
