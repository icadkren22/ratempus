cmake_minimum_required(VERSION 3.22.1)
project("directaudio")

add_library(
    directaudio
    SHARED
    direct_audio_jni.cpp
    usb_exclusive_output.cpp
)

find_library(log-lib log)

target_link_libraries(
    directaudio
    ${log-lib}
)
