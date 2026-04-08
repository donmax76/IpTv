cmake_minimum_required(VERSION 3.22.1)
project("fmradio_native")

# Single shared library:
#   - fm_dsp.cpp: native FM demodulator (zero-jitter audio)
#   - native_usb.c: direct USB ioctl wrapper with errno reporting
#
# NativeUsb.kt does System.loadLibrary("fmradio_native"), and NativeFmDsp.kt
# also references the same .so. Bundling them avoids loading two libraries
# and keeps the JNI symbols in one place.
add_library(fmradio_native SHARED
    fm_dsp.cpp
    native_usb.c
)

target_compile_options(fmradio_native PRIVATE
    -O3
    -ffast-math
    -funroll-loops
)

target_link_libraries(fmradio_native log)
