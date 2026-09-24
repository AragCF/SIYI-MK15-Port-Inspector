package com.mk15.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class Mk15NativeSerial {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String error = "";
        try {
            System.loadLibrary("mk15sdkserial");
            ok = true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        AVAILABLE = ok;
        LOAD_ERROR = error;
    }

    private Mk15NativeSerial() {}

    static Port open(String path, int baud) throws IOException {
        if (!AVAILABLE) {
            throw new IOException("Native serial library unavailable: " + LOAD_ERROR);
        }
        int fd = nativeOpen(path, baud);
        if (fd < 0) throw new IOException("nativeOpen errno=" + (-fd) + " path=" + path);
        return new Port(fd, path, baud);
    }

    static final class Port implements Closeable {
        private final int fd;
        private final String path;
        private final int baud;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Port(int fd, String path, int baud) {
            this.fd = fd;
            this.path = path;
            this.baud = baud;
        }

        int read(byte[] buffer, int timeoutMs) throws IOException {
            if (closed.get()) throw new IOException("Port is closed");
            int rc = nativeRead(fd, buffer, timeoutMs);
            if (rc < 0) throw new IOException("nativeRead errno=" + (-rc));
            return rc;
        }

        void write(byte[] data) throws IOException {
            if (closed.get()) throw new IOException("Port is closed");
            int rc = nativeWrite(fd, data);
            if (rc < 0) throw new IOException("nativeWrite errno=" + (-rc));
            if (rc != data.length) throw new IOException("nativeWrite short write " + rc + "/" + data.length);
        }

        String describe() {
            if (closed.get()) return path + " closed";
            String value = nativeDescribe(fd);
            return path + " baud=" + baud + " " + (value == null ? "" : value);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) nativeClose(fd);
        }
    }

    private static native int nativeOpen(String path, int baud);
    private static native int nativeRead(int fd, byte[] buffer, int timeoutMs);
    private static native int nativeWrite(int fd, byte[] data);
    private static native String nativeDescribe(int fd);
    private static native int nativeClose(int fd);
}
