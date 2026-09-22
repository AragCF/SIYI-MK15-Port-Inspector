package com.mk15.portinspector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SystemProbe {
    private SystemProbe() {}

    public static Map<String, String> collectDynamic() {
        Map<String, String> out = new LinkedHashMap<>();
        collectSwitchClass(out);
        collectGpio(out);
        collectExtcon(out);
        collectInterrupts(out);
        collectKnownNodes(out);
        return out;
    }

    private static void collectSwitchClass(Map<String, String> out) {
        File root = new File("/sys/class/switch");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        Arrays.sort(dirs);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String base = "system.switch." + safe(dir.getName()) + ".";
            putSmall(out, base + "state", new File(dir, "state"));
            putSmall(out, base + "name", new File(dir, "name"));
        }
    }

    private static void collectGpio(Map<String, String> out) {
        File root = new File("/sys/class/gpio");
        File[] dirs = root.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                if (!dir.isDirectory() || !dir.getName().startsWith("gpio")) continue;
                String base = "system.gpio." + safe(dir.getName()) + ".";
                putSmall(out, base + "value", new File(dir, "value"));
                putSmall(out, base + "direction", new File(dir, "direction"));
                putSmall(out, base + "edge", new File(dir, "edge"));
                putSmall(out, base + "active_low", new File(dir, "active_low"));
            }
        }

        File debug = new File("/sys/kernel/debug/gpio");
        if (debug.canRead()) {
            int index = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(debug))) {
                String line;
                while ((line = br.readLine()) != null && index < 256) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        out.put("system.gpio.debug." + (index++), trimmed);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectExtcon(Map<String, String> out) {
        File root = new File("/sys/class/extcon");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        Arrays.sort(dirs);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String base = "system.extcon." + safe(dir.getName()) + ".";
            putSmall(out, base + "state", new File(dir, "state"));
            putSmall(out, base + "name", new File(dir, "name"));
        }
    }

    private static void collectInterrupts(Map<String, String> out) {
        File file = new File("/proc/interrupts");
        if (!file.canRead()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int row = 0;
            while ((line = br.readLine()) != null && row < 512) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    row++;
                    continue;
                }
                String key = safe(trimmed.substring(0, colon).trim());
                String value = trimmed.substring(colon + 1).trim();
                out.put("system.interrupt." + key, value);
                row++;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectKnownNodes(Map<String, String> out) {
        String[] paths = {
                "/dev/ttyHS0",
                "/dev/ttyUSB0",
                "/dev/ttyACM0"
        };
        for (String path : paths) {
            File f = new File(path);
            String key = "system.node." + safe(path);
            out.put(key + ".exists", String.valueOf(f.exists()));
            out.put(key + ".readable", String.valueOf(f.canRead()));
            out.put(key + ".writable", String.valueOf(f.canWrite()));
            out.put(key + ".length", String.valueOf(f.length()));
            out.put(key + ".mtime", String.valueOf(f.lastModified()));
        }
    }

    private static void putSmall(Map<String, String> out, String key, File file) {
        if (!file.canRead() || file.length() > 4096) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && sb.length() < 4096) {
                if (sb.length() > 0) sb.append('|');
                sb.append(line.trim());
            }
            out.put(key, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        if (value == null) return "null";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.US);
    }
}
