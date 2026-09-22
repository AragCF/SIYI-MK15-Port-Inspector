import com.mk15.portinspector.ProbeDiffEngine;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProbeDiffSelfTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Map<String, String> state(String ch5, String rxBytes) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rc.usb.ch05", ch5);
        m.put("rc.usb.ch06", "1500");
        m.put("transport.usb.rxBytes", rxBytes);
        m.put("system.interrupt.42", rxBytes + " timer");
        return m;
    }

    public static void main(String[] args) {
        ProbeDiffEngine engine = new ProbeDiffEngine();
        engine.setBaseline(state("1050", "100"));

        ProbeDiffEngine.Result r1 = engine.compare(state("1500", "200"));
        require(r1.round == 1, "round 1 expected");
        require(!r1.candidates.isEmpty(), "candidates expected");
        require("rc.usb.ch05".equals(r1.candidates.get(0).key),
                "RC channel must outrank noisy counters after round 1");

        ProbeDiffEngine.Result r2 = engine.compare(state("1950", "300"));
        require(r2.round == 2, "round 2 expected");
        require("rc.usb.ch05".equals(r2.candidates.get(0).key),
                "RC channel must remain top candidate");
        require(r2.candidates.get(0).hits == 2, "RC channel must hit both rounds");
        require(r2.candidates.get(0).score > ProbeDiffEngine.weightFor("transport.usb.rxBytes"),
                "RC score must be stronger than transport byte counter");

        System.out.println("ALL PROBE DIFF SELF-TESTS PASSED");
    }
}
