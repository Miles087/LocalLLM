cmake_minimum_required(VERSION 3.22)
project(llama_android)

# 头文件路径
include_directories(
        D:/Projects/Android/Build_llama/llama.cpp/include
        D:/Projects/Android/Build_llama/llama.cpp/ggml/include
)

# 引入预编译库 - 每个只能定义一次！
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so)

# OpenMP
add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libomp.so)

# 编译您的 JNI 代码
add_library(llama-android SHARED llama-android.cpp)

target_link_libraries(llama-android
        llama
        ggml
        ggml-base
        ggml-cpu
        omp
        android
        log)
