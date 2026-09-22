package com.mk15.portinspector;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ChannelActivityTracker {
    private static final class State {
        int last;
        int min;
        int max;
        long changes;
        long samples;
        boolean initialized;
    }

    private final Map<String, State[]> bySource = new TreeMap<>();

    public synchronized void update(String source, int[] values) {
        if (source == null || values == null) return;
        String normalized = source.toLowerCase(Locale.US).replaceAll("[^a-z0-9_.-]", "_");
        State[] states = bySource.get(normalized);
        if (states == null || states.length != values.length) {
            states = new State[values.length];
            for (int i = 0; i < states.length; i++) states[i] = new State();
            bySource.put(normalized, states);
        }

        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            State s = states[i];
            if (!s.initialized) {
                s.initialized = true;
                s.last = value;
                s.min = value;
                s.max = value;
            } else {
                if (value != s.last) s.changes++;
                s.last = value;
                if (value < s.min) s.min = value;
                if (value > s.max) s.max = value;
            }
            s.samples++;
        }
    }

    public synchronized Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, State[]> e : bySource.entrySet()) {
            State[] states = e.getValue();
            for (int i = 0; i < states.length; i++) {
                State s = states[i];
                if (!s.initialized) continue;
                String base = String.format(Locale.US, "rcactivity.%s.ch%02d.", e.getKey(), i + 1);
                out.put(base + "last", String.valueOf(s.last));
                out.put(base + "min", String.valueOf(s.min));
                out.put(base + "max", String.valueOf(s.max));
                out.put(base + "changeCount", String.valueOf(s.changes));
                out.put(base + "samples", String.valueOf(s.samples));
            }
        }
        return out;
    }

    public synchronized void clear() {
        bySource.clear();
    }
}
