cmake_minimum_required(VERSION 3.22.1)
project(llamajni LANGUAGES C CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# llama.cpp 버전: CI가 실제 존재하는 태그를 골라 환경변수로 넘김 (없으면 b5000)
if(DEFINED ENV{LLAMA_TAG})
    set(LLAMA_TAG $ENV{LLAMA_TAG})
else()
    set(LLAMA_TAG b5000)
endif()
message(STATUS "llama.cpp tag: ${LLAMA_TAG}")

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(llamajni SHARED llama_jni.cpp)
target_link_libraries(llamajni PRIVATE llama android log)
# 내부 ggml 심볼을 숨겨 whisper 쪽 ggml과 충돌 방지 + 16KB 페이지 호환
target_link_options(llamajni PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
