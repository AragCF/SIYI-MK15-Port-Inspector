package com.mk15.sdk;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Mk15Controls implements Closeable {
    public static final int PHYSICAL_TYPE_BUTTON = 1;
    public static final int ENTITY_C = 2;
    public static final int ENTITY_D = 3;

    public enum Button { C, D }
    public enum State { RELEASED, PRESSED, INTERMEDIATE }

    public interface Listener {
        void onConnected(String nativeDescription);
        void onMapping(int cChannel, int dChannel);
        void onButtonChanged(Button button, State state, int rawValue, int channel);
        void onChannels(int[] channels);
        void onError(String message, Throwable error);
    }

    public static class Adapter implements Listener {
        @Override public void onConnected(String nativeDescription) {}
        @Override public void onMapping(int cChannel, int dChannel) {}
        @Override public void onButtonChanged(Button button, State state, int rawValue, int channel) {}
        @Override public void onChannels(int[] channels) {}
        @Override public void onError(String message, Throwable error) {}
    }

    private final Listener listener;
    private final String path;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final Mk15Protocol.Parser parser;

    private volatile Mk15NativeSerial.Port port;
    private volatile Thread readerThread;
    private volatile Thread initThread;
    private volatile int cChannel = -1;
    private volatile int dChannel = -1;
    private volatile State lastCState;
    private volatile State lastDState;

    public Mk15Controls(Listener listener) {
        this(Mk15Evidence.UART_PATH, listener);
    }

    public Mk15Controls(String path, Listener listener) {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("path is empty");
        this.path = path;
        this.listener = listener == null ? new Adapter() : listener;
        this.parser = new Mk15Protocol.Parser(new Mk15Protocol.FrameListener() {
            @Override public void onFrame(Mk15Protocol.Frame frame) {
                handleFrame(frame);
            }

            @Override public void onBadCrc(byte[] candidate) {
                fireError("SIYI CRC mismatch: " + Mk15Protocol.hex(candidate), null);
            }
        });
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;

        Mk15NativeSerial.Port opened = Mk15NativeSerial.open(path, Mk15Evidence.UART_BAUD);
        port = opened;
        running.set(true);
        fireConnected(opened.describe());

        readerThread = new Thread(new Runnable() {
            @Override public void run() {
                readerLoop();
            }
        }, "mk15-sdk-reader");
        readerThread.start();

        initThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    requestMapping();
                    sleepQuiet(300);
                    startChannelStream4Hz();
                } catch (Throwable t) {
                    if (running.get()) fireError("MK15 initialization failed", t);
                }
            }
        }, "mk15-sdk-init");
        initThread.start();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getCChannel() {
        return cChannel;
    }

    public int getDChannel() {
        return dChannel;
    }

    public String describeTransport() {
        Mk15NativeSerial.Port current = port;
        return current == null ? "disconnected" : current.describe();
    }

    public void requestMapping() throws IOException {
        write(Mk15Protocol.mappingRequest(nextSeq()));
    }

    public void startChannelStream4Hz() throws IOException {
        sendThreeTimes(Mk15Protocol.channelStreamRequest(2));
    }

    public void stopChannelStream() throws IOException {
        sendThreeTimes(Mk15Protocol.channelStreamRequest(0));
    }

    public static State classify(int rawValue) {
        if (Mk15Evidence.isReleased(rawValue)) return State.RELEASED;
        if (Mk15Evidence.isPressed(rawValue)) return State.PRESSED;
        return State.INTERMEDIATE;
    }

    private void readerLoop() {
        byte[] buffer = new byte[2048];
        try {
            while (running.get()) {
                Mk15NativeSerial.Port current = port;
                if (current == null) break;
                int n = current.read(buffer, 500);
                if (n > 0) parser.append(buffer, n);
            }
        } catch (Throwable t) {
            if (running.get()) fireError("MK15 UART read failed", t);
        }
    }

    private void handleFrame(Mk15Protocol.Frame frame) {
        if (frame.cmdId == Mk15Protocol.CMD_ALL_CHANNEL_MAPPINGS && frame.data.length >= 32) {
            Mk15Protocol.Mapping mapping = Mk15Protocol.parseMapping(frame.data);
            int c = mapping.findChannel(PHYSICAL_TYPE_BUTTON, ENTITY_C);
            int d = mapping.findChannel(PHYSICAL_TYPE_BUTTON, ENTITY_D);
            cChannel = c;
            dChannel = d;
            if (c <= 0 || d <= 0) {
                fireError("C/D mapping was not found in SIYI 0x48", null);
            } else {
                fireMapping(c, d);
            }
            return;
        }

        if (frame.cmdId == Mk15Protocol.CMD_CHANNEL_DATA && frame.data.length >= 32) {
            int[] channels = Mk15Protocol.parseChannels(frame.data);
            fireChannels(channels);
            emitButton(Button.C, cChannel, channels);
            emitButton(Button.D, dChannel, channels);
        }
    }

    private void emitButton(Button button, int channel, int[] channels) {
        if (channel < 1 || channel > channels.length) return;
        int raw = channels[channel - 1];
        State state = classify(raw);

        if (button == Button.C) {
            if (state != lastCState) {
                lastCState = state;
                fireButton(button, state, raw, channel);
            }
        } else if (state != lastDState) {
            lastDState = state;
            fireButton(button, state, raw, channel);
        }
    }

    private int nextSeq() {
        int v = sequence.getAndIncrement() & 0xFFFF;
        if (v == 0) v = sequence.getAndIncrement() & 0xFFFF;
        return v;
    }

    private void write(byte[] data) throws IOException {
        Mk15NativeSerial.Port current = port;
        if (!running.get() || current == null) throw new IOException("MK15 SDK is not started");
        current.write(data);
    }

    private void sendThreeTimes(byte[] data) throws IOException {
        for (int i = 0; i < 3; i++) {
            write(data);
            sleepQuiet(60);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fireConnected(String description) {
        try { listener.onConnected(description); } catch (Throwable ignored) {}
    }

    private void fireMapping(int c, int d) {
        try { listener.onMapping(c, d); } catch (Throwable ignored) {}
    }

    private void fireButton(Button button, State state, int raw, int channel) {
        try { listener.onButtonChanged(button, state, raw, channel); } catch (Throwable ignored) {}
    }

    private void fireChannels(int[] channels) {
        try { listener.onChannels(Arrays.copyOf(channels, channels.length)); } catch (Throwable ignored) {}
    }

    private void fireError(String message, Throwable error) {
        try { listener.onError(message, error); } catch (Throwable ignored) {}
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) return;
        try {
            Mk15NativeSerial.Port current = port;
            if (current != null) {
                byte[] off = Mk15Protocol.channelStreamRequest(0);
                for (int i = 0; i < 3; i++) {
                    try {
                        current.write(off);
                        sleepQuiet(60);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        } finally {
            Mk15NativeSerial.Port current = port;
            port = null;
            if (current != null) {
                try { current.close(); } catch (Throwable ignored) {}
            }
            Thread r = readerThread;
            if (r != null) r.interrupt();
            Thread init = initThread;
            if (init != null) init.interrupt();
            readerThread = null;
            initThread = null;
        }
    }
}
