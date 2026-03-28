cmake_minimum_required(VERSION 3.18.1)
project("fmradio_native")

# Native USB (existing)
add_library(fmradio_native SHARED native_usb.c)
target_link_libraries(fmradio_native log)

# Native DSP — FM demodulator in C++ for zero-jitter audio
add_library(fmradio_dsp SHARED fm_dsp.cpp)
target_compile_options(fmradio_dsp PRIVATE -O3 -ffast-math)
target_link_libraries(fmradio_dsp log)
