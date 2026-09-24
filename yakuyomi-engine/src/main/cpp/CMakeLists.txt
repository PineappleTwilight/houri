cmake_minimum_required(VERSION 3.22.1)
project(upscale_ncnn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reuse the ARM NCNN archives already shipped by the yakuyomi-engine submodule.
# Keeping the bridge in the root module avoids a second checkout/branch for native
# upscaling while still avoiding a second NCNN build.
set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/yakuyomi-engine/engine/src/main/cpp/ncnn")
set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")

if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "NCNN package not found for ABI ${ANDROID_ABI}: ${ncnn_DIR}")
endif()

find_package(ncnn REQUIRED)

add_library(upscale_ncnn SHARED upscale_jni.cpp)
target_compile_options(upscale_ncnn PRIVATE -O3)
target_link_libraries(upscale_ncnn ncnn log android)
