#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

static speed_t baud_to_speed(int baud) {
    switch (baud) {
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
#ifdef B230400
        case 230400: return B230400;
#endif
        default: return (speed_t)0;
    }
}

static int speed_to_baud(speed_t speed) {
    switch (speed) {
        case B4800: return 4800;
        case B9600: return 9600;
        case B19200: return 19200;
        case B38400: return 38400;
        case B57600: return 57600;
        case B115200: return 115200;
#ifdef B230400
        case B230400: return 230400;
#endif
        default: return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_mk15_portinspector_NativeSerial_nativeOpen(
        JNIEnv *env, jclass clazz, jstring path_, jint baud) {
    (void) clazz;

    const char *path = (*env)->GetStringUTFChars(env, path_, NULL);
    if (path == NULL) return -ENOMEM;

    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    int open_errno = errno;
    (*env)->ReleaseStringUTFChars(env, path_, path);
    if (fd < 0) return -open_errno;

    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    speed_t speed = baud_to_speed((int) baud);
    if (speed == 0) {
        close(fd);
        return -EINVAL;
    }

    /*
     * Do not rely on cfmakeraw alone. Android/bionic cfmakeraw deliberately
     * leaves several historical input flags untouched. For a binary SIYI
     * frame stream we need all input/output/local translations disabled.
     */
    tio.c_iflag = 0;
    tio.c_oflag = 0;
    tio.c_lflag = 0;

    tio.c_cflag &= ~(CSIZE | PARENB | CSTOPB);
#ifdef CRTSCTS
    tio.c_cflag &= ~CRTSCTS;
#endif
    tio.c_cflag |= CS8 | CLOCAL | CREAD;

    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;

    if (cfsetispeed(&tio, speed) != 0 || cfsetospeed(&tio, speed) != 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    tcflush(fd, TCIOFLUSH);

    int flags = fcntl(fd, F_GETFL);
    if (flags >= 0) {
        (void) fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_mk15_portinspector_NativeSerial_nativeRead(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer_, jint timeout_ms) {
    (void) clazz;
    if (buffer_ == NULL) return -EINVAL;

    jsize capacity = (*env)->GetArrayLength(env, buffer_);
    if (capacity <= 0) return -EINVAL;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pr;
    do {
        pr = poll(&pfd, 1, timeout_ms);
    } while (pr < 0 && errno == EINTR);

    if (pr == 0) return 0;
    if (pr < 0) return -errno;
    if ((pfd.revents & (POLLERR | POLLNVAL)) != 0) return -EIO;

    jbyte *buffer = (*env)->GetByteArrayElements(env, buffer_, NULL);
    if (buffer == NULL) return -ENOMEM;

    ssize_t n;
    do {
        n = read(fd, buffer, (size_t) capacity);
    } while (n < 0 && errno == EINTR);

    if (n > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer_, buffer, 0);
        return (jint) n;
    }

    int e = (n < 0) ? errno : EIO;
    (*env)->ReleaseByteArrayElements(env, buffer_, buffer, JNI_ABORT);
    return -e;
}

JNIEXPORT jint JNICALL
Java_com_mk15_portinspector_NativeSerial_nativeWrite(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray data_) {
    (void) clazz;
    if (data_ == NULL) return -EINVAL;

    jsize length = (*env)->GetArrayLength(env, data_);
    jbyte *data = (*env)->GetByteArrayElements(env, data_, NULL);
    if (data == NULL) return -ENOMEM;

    ssize_t total = 0;
    while (total < length) {
        ssize_t n = write(fd, data + total, (size_t) (length - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            int e = errno;
            (*env)->ReleaseByteArrayElements(env, data_, data, JNI_ABORT);
            return -e;
        }
        if (n == 0) break;
        total += n;
    }

    (void) tcdrain(fd);
    (*env)->ReleaseByteArrayElements(env, data_, data, JNI_ABORT);
    return (jint) total;
}

JNIEXPORT jstring JNICALL
Java_com_mk15_portinspector_NativeSerial_nativeDescribe(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;

    struct termios tio;
    char out[1024];

    if (tcgetattr(fd, &tio) != 0) {
        snprintf(out, sizeof(out), "tcgetattr_errno=%d", errno);
        return (*env)->NewStringUTF(env, out);
    }

    int binary_clean =
            tio.c_iflag == 0 &&
            tio.c_oflag == 0 &&
            tio.c_lflag == 0 &&
            (tio.c_cflag & CSIZE) == CS8 &&
            (tio.c_cflag & PARENB) == 0 &&
            (tio.c_cflag & CSTOPB) == 0;

    snprintf(out, sizeof(out),
             "native=true binaryClean=%s ispeed=%d ospeed=%d "
             "iflag=0x%08lx oflag=0x%08lx cflag=0x%08lx lflag=0x%08lx "
             "VMIN=%d VTIME=%d",
             binary_clean ? "true" : "false",
             speed_to_baud(cfgetispeed(&tio)),
             speed_to_baud(cfgetospeed(&tio)),
             (unsigned long) tio.c_iflag,
             (unsigned long) tio.c_oflag,
             (unsigned long) tio.c_cflag,
             (unsigned long) tio.c_lflag,
             (int) tio.c_cc[VMIN],
             (int) tio.c_cc[VTIME]);

    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jint JNICALL
Java_com_mk15_portinspector_NativeSerial_nativeClose(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd < 0) return 0;
    if (close(fd) == 0) return 0;
    return -errno;
}
