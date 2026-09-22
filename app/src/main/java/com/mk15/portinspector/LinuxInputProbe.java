package com.mk15.portinspector;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LinuxInputProbe {
    private static final class Session {
        final String eventName;
        final String deviceName;
        final File file;
        final AtomicLong rxBytes = new AtomicLong();
        final AtomicLong chunks = new AtomicLong();
        volatile String lastHex = "";
        volatile boolean running;
        volatile Thread thread;
        volatile Closeable closer;

        Session(String eventName, String deviceName, File file) {
            this.eventName = eventName;
            this.deviceName = deviceName;
            this.file = file;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public synchronized void start() {
        File dir = new File("/dev/input");
        File[] files = dir.listFiles();
        if (files == null) return;

        List<File> events = new ArrayList<>();
        for (File file : files) {
            if (file.getName().startsWith("event")) events.add(file);
        }
        Collections.sort(events, Comparator.comparing(File::getName));

        for (File file : events) {
            String eventName = file.getName();
            if (sessions.containsKey(eventName)) continue;
            if (!file.canRead()) continue;

            String deviceName = readSmall("/sys/class/input/" + eventName + "/device/name");
            if (deviceName.isEmpty()) deviceName = "unknown";

            final Session session = new Session(eventName, deviceName, file);
            try {
                final FileInputStream input = new FileInputStream(file);
                session.closer = input;
                session.running = true;
                sessions.put(eventName, session);

                session.thread = new Thread(() -> {
                    byte[] buffer = new byte[384];
                    try {
                        while (session.running) {
                            int n = input.read(buffer);
                            if (n < 0) break;
                            if (n == 0) continue;
                            session.rxBytes.addAndGet(n);
                            session.chunks.incrementAndGet();
                            byte[] shown = Arrays.copyOf(buffer, Math.min(n, 96));
                            session.lastHex = SiyiProtocol.hex(shown)
                                    + (n > shown.length ? " ..." : "");
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        session.running = false;
                        try { input.close(); } catch (Throwable ignored) {}
                    }
                }, "mk15-linux-input-" + eventName);
                session.thread.start();
            } catch (Throwable ignored) {
                session.running = false;
            }
        }
    }

    public synchronized void stop() {
        for (Session session : sessions.values()) {
            session.running = false;
            Thread t = session.thread;
            if (t != null) t.interrupt();
            Closeable closer = session.closer;
            if (closer != null) {
                try { closer.close(); } catch (Throwable ignored) {}
            }
        }
        sessions.clear();
    }

    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();

        File dir = new File("/dev/input");
        File[] files = dir.listFiles();
        if (files != null) {
            List<File> events = new ArrayList<>();
            for (File file : files) {
                if (file.getName().startsWith("event")) events.add(file);
            }
            Collections.sort(events, Comparator.comparing(File::getName));
            for (File file : events) {
                String event = file.getName();
                String deviceName = readSmall("/sys/class/input/" + event + "/device/name");
                if (deviceName.isEmpty()) deviceName = "unknown";
                String prefix = prefix(deviceName, event);
                out.put(prefix + "readable", String.valueOf(file.canRead()));
                out.put(prefix + "writable", String.valueOf(file.canWrite()));
            }
        }

        for (Session s : sessions.values()) {
            String prefix = prefix(s.deviceName, s.eventName);
            out.put(prefix + "running", String.valueOf(s.running));
            out.put(prefix + "rxBytes", String.valueOf(s.rxBytes.get()));
            out.put(prefix + "chunks", String.valueOf(s.chunks.get()));
            out.put(prefix + "lastHex", s.lastHex);
        }
        return out;
    }

    private static String prefix(String deviceName, String event) {
        return "linuxinput." + safe(deviceName) + "." + safe(event) + ".";
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String readSmall(String path) {
        File file = new File(path);
        if (!file.canRead()) return "";
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
