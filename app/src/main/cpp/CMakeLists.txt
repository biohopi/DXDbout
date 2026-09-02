cmake_minimum_required(VERSION 3.22.1)

project("turbodns_gguf_chat" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan backend" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Disable native host CPU build" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libraries" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)

add_subdirectory(llama.cpp)

add_library(llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(llama_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(llama_jni
    llama
    ggml
    log
    android
)
