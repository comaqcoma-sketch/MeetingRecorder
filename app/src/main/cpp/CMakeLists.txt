cmake_minimum_required(VERSION 3.22.1)
project(whisperjni LANGUAGES C CXX)

# 디버그 빌드에서도 항상 최적화 (-O3). 최적화 없으면 변환이 수십 배 느려짐
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(whisperjni SHARED whisper_jni.cpp)
target_link_libraries(whisperjni PRIVATE whisper android log)
# Android 15+ 16KB 페이지 기기 호환
target_link_options(whisperjni PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
