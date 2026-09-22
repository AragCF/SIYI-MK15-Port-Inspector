import com.mk15.portinspector.ChannelActivityTracker;

import java.util.Map;

public final class ChannelActivityTrackerSelfTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static int[] values(int ch10, int ch11) {
        int[] v = new int[16];
        for (int i = 0; i < v.length; i++) v[i] = 1500;
        v[9] = ch10;
        v[10] = ch11;
        return v;
    }

    public static void main(String[] args) {
        ChannelActivityTracker t = new ChannelActivityTracker();

        t.update("UART0", values(1000, 1000));
        t.update("UART0", values(1900, 1000));
        t.update("UART0", values(1000, 1000));

        Map<String, String> first = t.snapshot();
        require("2".equals(first.get("rcactivity.uart0.ch10.changeCount")),
                "momentary C press/release must remain visible in changeCount");
        require("1900".equals(first.get("rcactivity.uart0.ch10.max")),
                "momentary maximum must be retained");
        require("1000".equals(first.get("rcactivity.uart0.ch10.last")),
                "last may already be back at rest");

        t.update("UART0", values(1000, 1900));
        t.update("UART0", values(1000, 1000));
        Map<String, String> second = t.snapshot();
        require("2".equals(second.get("rcactivity.uart0.ch11.changeCount")),
                "momentary D press/release must remain visible in changeCount");

        System.out.println("ALL CHANNEL ACTIVITY SELF-TESTS PASSED");
    }
}
