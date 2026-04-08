cmake_minimum_required(VERSION 3.22.1)
project("fmradio_native")

# Single shared library for the native FM DSP.
#
# NOTE: intentionally does NOT build native_usb.c — that file uses
# <linux/usbdevice_fs.h> which isn't in every NDK sysroot and broke the
# build. NativeUsb.kt falls back to the Java UsbDeviceConnection API
# (loadLibrary simply fails for "fmradio_native", isNativeAvailable=false),
# which is how the project ran historically.
add_library(fmradio_dsp SHARED fm_dsp.cpp)

set_target_properties(fmradio_dsp PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_compile_options(fmradio_dsp PRIVATE
    -O3
    -ffast-math
    -funroll-loops
)

target_link_libraries(fmradio_dsp log)
