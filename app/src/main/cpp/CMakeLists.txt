cmake_minimum_required(VERSION 3.22.1)

project("edge20pro_vendor_probe_v4")

add_library(
        edge20pro_vendor_probe_v4
        SHARED
        native_vendor_probe_v6.cpp
)

find_library(
        log-lib
        log
)

find_library(
        camera2ndk-lib
        camera2ndk
)

target_compile_features(
        edge20pro_vendor_probe_v4
        PRIVATE
        cxx_std_17
)

target_compile_options(
        edge20pro_vendor_probe_v4
        PRIVATE
        -Wall
        -Wextra
        -Wpedantic
)

target_link_libraries(
        edge20pro_vendor_probe_v4
        ${camera2ndk-lib}
        ${log-lib}
)
