cmake_minimum_required(VERSION 3.22.1)
project("directaudio")

add_library(
    directaudio
    SHARED
    direct_audio_jni.cpp
)

find_library(log-lib log)

target_link_libraries(
    directaudio
    ${log-lib}
)
