cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

project(media3_exoplayer_jni C CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(media3_exoplayer_jni
            SHARED
            exoplayer_jni.cpp)

target_compile_options(media3_exoplayer_jni
                       PRIVATE "-Os"
                               "-fno-exceptions"
                               "-fno-rtti"
                               "-fvisibility=hidden"
                               "-ffunction-sections"
                               "-fdata-sections")

target_link_options(media3_exoplayer_jni
                    PRIVATE "-Wl,--gc-sections"
                            "-Wl,--exclude-libs,ALL"
                            "-Wl,-z,max-page-size=16384")
