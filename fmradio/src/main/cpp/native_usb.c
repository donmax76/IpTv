/*
 * Native USB control transfer wrapper for Android.
 * Provides direct ioctl access with proper errno reporting.
 * This bypasses Java's UsbDeviceConnection.controlTransfer() which
 * only returns -1 without errno, making USB debugging impossible.
 */
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

JNIEXPORT jint JNICALL
Java_com_fmradio_rtlsdr_NativeUsb_nativeControlTransfer(
    JNIEnv *env, jclass cls,
    jint fd, jint requestType, jint request,
    jint value, jint index,
    jbyteArray buffer, jint length, jint timeout)
{
    struct usbdevfs_ctrltransfer ctrl;
    memset(&ctrl, 0, sizeof(ctrl));

    ctrl.bRequestType = requestType;
    ctrl.bRequest = request;
    ctrl.wValue = value;
    ctrl.wIndex = index;
    ctrl.wLength = length;
    ctrl.timeout = timeout;

    jbyte *buf = NULL;
    if (buffer && length > 0) {
        buf = (*env)->GetByteArrayElements(env, buffer, NULL);
        if (!buf) return -9999;
        ctrl.data = buf;
    } else {
        ctrl.data = NULL;
    }

    int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    int saved_errno = errno;

    if (buf) {
        (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    }

    /* Return: >= 0 = bytes transferred, < 0 = -errno */
    if (ret < 0) {
        return -(saved_errno);
    }
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_fmradio_rtlsdr_NativeUsb_nativeBulkTransfer(
    JNIEnv *env, jclass cls,
    jint fd, jint endpoint,
    jbyteArray buffer, jint length, jint timeout)
{
    struct usbdevfs_bulktransfer bulk;
    memset(&bulk, 0, sizeof(bulk));

    bulk.ep = endpoint;
    bulk.len = length;
    bulk.timeout = timeout;

    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) return -9999;
    bulk.data = buf;

    int ret = ioctl(fd, USBDEVFS_BULKTRANSFER, &bulk);
    int saved_errno = errno;

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    if (ret < 0) {
        return -(saved_errno);
    }
    return ret;
}
