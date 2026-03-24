cmake_minimum_required(VERSION 3.18.1)
project("fmradio_native")

add_library(fmradio_native SHARED native_usb.c)
target_link_libraries(fmradio_native log)
