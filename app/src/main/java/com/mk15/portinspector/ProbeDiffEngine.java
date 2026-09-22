package com.mk15.portinspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProbeDiffEngine {
    public static final class Change {
        public final String key;
        public final String before;
        public final String after;
        public final double weight;

        Change(String key, String before, String after, double weight) {
            this.key = key;
            this.before = before;
            this.after = after;
            this.weight = weight;
        }
    }

    public static final class Candidate {
        public final String key;
        public final int hits;
        public final int rounds;
        public final double score;
        public final String before;
        public final String after;

        Candidate(String key, int hits, int rounds, double score, String before, String after) {
            this.key = key;
            this.hits = hits;
            this.rounds = rounds;
            this.score = score;
            this.before = before;
            this.after = after;
        }
    }

    public static final class Result {
        public final int round;
        public final List<Change> changes;
        public final List<Candidate> candidates;

        Result(int round, List<Change> changes, List<Candidate> candidates) {
            this.round = round;
            this.changes = changes;
            this.candidates = candidates;
        }
    }

    private Map<String, String> baseline;
    private final Map<String, Integer> hits = new HashMap<>();
    private final Map<String, Double> scores = new HashMap<>();
    private final Map<String, String> lastBefore = new HashMap<>();
    private final Map<String, String> lastAfter = new HashMap<>();
    private int rounds;

    public synchronized void setBaseline(Map<String, String> snapshot) {
        baseline = new LinkedHashMap<>(snapshot);
        hits.clear();
        scores.clear();
        lastBefore.clear();
        lastAfter.clear();
        rounds = 0;
    }

    public synchronized boolean hasBaseline() {
        return baseline != null;
    }

    public synchronized int getRounds() {
        return rounds;
    }

    public synchronized void reset() {
        baseline = null;
        hits.clear();
        scores.clear();
        lastBefore.clear();
        lastAfter.clear();
        rounds = 0;
    }

    public synchronized Result compare(Map<String, String> current) {
        if (baseline == null) {
            setBaseline(current);
            return new Result(0, Collections.<Change>emptyList(), Collections.<Candidate>emptyList());
        }

        rounds++;
        Set<String> keys = new HashSet<>();
        keys.addAll(baseline.keySet());
        keys.addAll(current.keySet());

        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            String before = baseline.get(key);
            String after = current.get(key);
            if (Objects.equals(before, after)) continue;

            double weight = weightFor(key);
            Change change = new Change(key, before, after, weight);
            changes.add(change);

            hits.put(key, hits.containsKey(key) ? hits.get(key) + 1 : 1);
            scores.put(key, (scores.containsKey(key) ? scores.get(key) : 0.0) + weight);
            lastBefore.put(key, before);
            lastAfter.put(key, after);
        }

        Collections.sort(changes, new Comparator<Change>() {
            @Override
            public int compare(Change a, Change b) {
                int w = Double.compare(b.weight, a.weight);
                if (w != 0) return w;
                return a.key.compareTo(b.key);
            }
        });

        List<Candidate> candidates = new ArrayList<>();
        for (String key : hits.keySet()) {
            candidates.add(new Candidate(
                    key,
                    hits.get(key),
                    rounds,
                    scores.get(key),
                    lastBefore.get(key),
                    lastAfter.get(key)
            ));
        }

        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                int s = Double.compare(b.score, a.score);
                if (s != 0) return s;
                int h = Integer.compare(b.hits, a.hits);
                if (h != 0) return h;
                return a.key.compareTo(b.key);
            }
        });

        baseline = new LinkedHashMap<>(current);
        return new Result(rounds, changes, candidates);
    }

    public static double weightFor(String key) {
        if (key == null) return 0.0;
        if (key.startsWith("rcactivity.") && key.endsWith(".changeCount")) return 12.0;
        if (key.startsWith("rcactivity.") && (key.endsWith(".min") || key.endsWith(".max"))) return 8.0;
        if (key.startsWith("rcactivity.") && key.endsWith(".last")) return 7.0;
        if (key.startsWith("rcactivity.") && key.endsWith(".samples")) return 0.05;
        if (key.startsWith("rc.")) return 8.0;
        if (key.startsWith("input.motion.") && key.endsWith(".value")) return 9.0;
        if (key.startsWith("input.key.") && key.endsWith(".state")) return 9.0;
        if (key.startsWith("input.") && key.endsWith(".eventCount")) return 10.0;
        if (key.startsWith("system.switch.")) return 8.0;
        if (key.startsWith("system.gpio.") && key.endsWith(".value")) return 8.0;
        if (key.startsWith("system.gpio.debug.")) return 0.20;
        if (key.startsWith("system.extcon.") && key.endsWith(".state")) return 6.0;
        if (key.startsWith("linuxinput.") && key.contains("gpio-keys") && key.endsWith(".rxBytes")) return 9.0;
        if (key.startsWith("linuxinput.") && key.contains("gpio-keys") && key.endsWith(".chunks")) return 8.0;
        if (key.startsWith("linuxinput.") && key.contains("goodix-ts")) return 0.05;
        if (key.startsWith("linuxinput.") && (key.endsWith(".rxBytes") || key.endsWith(".chunks"))) return 4.0;
        if (key.startsWith("linuxinput.") && key.endsWith(".lastHex")) return 3.0;
        if (key.contains(".lastHex")) return 1.5;
        if (key.contains(".eventCount")) return 1.0;
        if (key.contains(".rxBytes") || key.contains(".rxChunks") || key.contains(".txBytes")) return 0.10;
        if (key.startsWith("system.interrupt.")) return 0.02;
        return 0.50;
    }
}
