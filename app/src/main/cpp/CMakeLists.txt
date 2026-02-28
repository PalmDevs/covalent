cmake_minimum_required(VERSION 3.13)
set(CMAKE_VERBOSE_MAKEFILE on)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--unresolved-symbols=ignore-all")

project("covalent" C CXX)

add_library(${CMAKE_PROJECT_NAME} SHARED
        lib.cpp
)

include(FetchContent)

FetchContent_Declare(
    fbjni_repo
    GIT_REPOSITORY https://github.com/facebookincubator/fbjni.git
    # @Target: RN 0.81.6 uses fbjni v0.7.0
    GIT_TAG        v0.7.0
)

FetchContent_GetProperties(fbjni_repo)
if(NOT fbjni_repo_POPULATED)
    FetchContent_Populate(fbjni_repo)
endif()

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_SOURCE_DIR}/include
        ${fbjni_repo_SOURCE_DIR}/cxx
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
)

# Bypass undefined symbols in the shared library, as they will be resolved at runtime
string(REGEX REPLACE "-Wl,--no-undefined( |$)" " " CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS}")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--unresolved-symbols=ignore-all")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--allow-shlib-undefined")