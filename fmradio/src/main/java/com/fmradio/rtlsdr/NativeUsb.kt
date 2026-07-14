package com.fmradio.rtlsdr

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.fmradio.dsp.DebugLog

/**
 * Native USB control transfer wrapper.
 * Uses direct Linux ioctl instead of Java's UsbDeviceConnection.controlTransfer().
 * Returns -errno on failure instead of just -1, enabling proper USB debugging.
 *
 * Falls back to Java API if native library is not available.
 */
object NativeUsb {

    private const val TAG = "NativeUsb"

    var isNativeAvailable = false
        private set

    init {
        try {
            System.loadLibrary("fmradio_native")
            isNativeAvailable = true
            Log.i(TAG, "Native USB library loaded")
        } catch (e: UnsatisfiedLinkError) {
            isNativeAvailable = false
            Log.w(TAG, "Native USB library not available, using Java API fallback")
        }
    }

    /**
     * Perform USB control transfer.
     * Returns bytes transferred on success, or negative errno on failure.
     * Common errno values:
     *   -1  (EPERM)  = permission denied
     *   -5  (EIO)    = I/O error
     *   -19 (ENODEV) = device disconnected
     *   -32 (EPIPE)  = endpoint stalled (STALL)
     *   -110 (ETIMEDOUT) = transfer timed out
     */
    fun controlTransfer(
        conn: UsbDeviceConnection,
        requestType: Int, request: Int,
        value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeout: Int
    ): Int {
        if (isNativeAvailable) {
            val fd = conn.fileDescriptor
            return nativeControlTransfer(fd, requestType, request, value, index, buffer, length, timeout)
        }
        // Fallback to Java API
        return conn.controlTransfer(requestType, request, value, index, buffer, length, timeout)
    }

    /**
     * Perform USB bulk transfer with errno reporting.
     */
    fun bulkTransfer(
        conn: UsbDeviceConnection,
        endpoint: Int,
        buffer: ByteArray, length: Int, timeout: Int
    ): Int {
        if (isNativeAvailable) {
            val fd = conn.fileDescriptor
            return nativeBulkTransfer(fd, endpoint, buffer, length, timeout)
        }
        return -1 // Can't do bulk via Java without UsbEndpoint object
    }

    /**
     * Translate errno to human-readable string for logging.
     */
    fun errnoName(result: Int): String {
        if (result >= 0) return "OK($result)"
        return when (-result) {
            1 -> "EPERM(permission denied)"
            5 -> "EIO(I/O error)"
            11 -> "EAGAIN(try again)"
            13 -> "EACCES(permission denied)"
            16 -> "EBUSY(device busy)"
            19 -> "ENODEV(no device)"
            22 -> "EINVAL(invalid argument)"
            32 -> "EPIPE(stall/broken pipe)"
            62 -> "ETIME(timer expired)"
            110 -> "ETIMEDOUT(timeout)"
            else -> "errno=${-result}"
        }
    }

    // Native methods
    private external fun nativeControlTransfer(
        fd: Int, requestType: Int, request: Int,
        value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeout: Int
    ): Int

    private external fun nativeBulkTransfer(
        fd: Int, endpoint: Int,
        buffer: ByteArray, length: Int, timeout: Int
    ): Int
}
