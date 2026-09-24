import com.mk15.sdk.Mk15Evidence;
import com.mk15.sdk.Mk15Protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Mk15SdkSelfTest {
    private static byte[] hex(String s) {
        String[] pp = s.trim().split("\\s+");
        byte[] out = new byte[pp.length];
        for (int i = 0; i < pp.length; i++) out[i] = (byte) Integer.parseInt(pp[i], 16);
        return out;
    }

    private static void eq(String name, String actual, String expected) {
        if (!actual.equalsIgnoreCase(expected)) {
            throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
        }
        System.out.println("OK: " + name + " = " + actual);
    }

    private static byte[] response(int seq, int cmd, byte[] data) {
        byte[] f = new byte[8 + data.length + 2];
        f[0] = 0x55; f[1] = 0x66; f[2] = 0;
        f[3] = (byte) (data.length & 0xff);
        f[4] = (byte) ((data.length >> 8) & 0xff);
        f[5] = (byte) (seq & 0xff); f[6] = (byte) ((seq >> 8) & 0xff);
        f[7] = (byte) cmd;
        System.arraycopy(data, 0, f, 8, data.length);
        int crc = Mk15Protocol.crc16(f, 0, 8 + data.length);
        f[8 + data.length] = (byte) (crc & 0xff);
        f[9 + data.length] = (byte) ((crc >> 8) & 0xff);
        return f;
    }

    public static void main(String[] args) {
        eq("SDK 0x42 4Hz", Mk15Protocol.hex(Mk15Protocol.channelStreamRequest(2)),
                "55 66 01 01 00 00 00 42 02 B5 C0");
        eq("SDK 0x42 OFF", Mk15Protocol.hex(Mk15Protocol.channelStreamRequest(0)),
                "55 66 01 01 00 00 00 42 00 F7 E0");

        byte[] mappingData = hex(
                "00 00 00 01 00 02 00 03 05 00 05 01 05 02 01 00 "
              + "01 01 01 02 01 03 00 04 00 05 02 01 00 04 01 01");
        Mk15Protocol.Mapping mapping = Mk15Protocol.parseMapping(mappingData);
        if (mapping.findChannel(1, 2) != 10) throw new AssertionError("C must map to CH10");
        if (mapping.findChannel(1, 3) != 11) throw new AssertionError("D must map to CH11");

        byte[] channelData = new byte[32];
        for (int i = 0; i < 16; i++) {
            int value = 1500;
            if (i == 9) value = 1950;
            if (i == 10) value = 1050;
            channelData[i * 2] = (byte) (value & 0xff);
            channelData[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
        }
        int[] values = Mk15Protocol.parseChannels(channelData);
        if (values[9] != 1950 || values[10] != 1050) {
            throw new AssertionError("C/D evidence values were not decoded");
        }
        if (!Mk15Evidence.isPressed(1950) || !Mk15Evidence.isReleased(1050)) {
            throw new AssertionError("Verified values do not match SDK thresholds");
        }

        final List<Mk15Protocol.Frame> frames = new ArrayList<>();
        Mk15Protocol.Parser parser = new Mk15Protocol.Parser(new Mk15Protocol.FrameListener() {
            @Override public void onFrame(Mk15Protocol.Frame frame) { frames.add(frame); }
            @Override public void onBadCrc(byte[] candidate) { throw new AssertionError("Unexpected bad CRC"); }
        });
        byte[] frame = response(77, 0x42, channelData);
        parser.append(frame, 7);
        parser.append(Arrays.copyOfRange(frame, 7, frame.length), frame.length - 7);
        if (frames.size() != 1 || frames.get(0).cmdId != 0x42) {
            throw new AssertionError("Fragmented 0x42 frame parse failed");
        }

        if (Mk15Evidence.VERIFIED_CHANNEL_FRAMES != 391
                || Mk15Evidence.VERIFIED_LABELLED_ACTIONS != 28
                || Mk15Evidence.VERIFIED_C_PRESSES != 14
                || Mk15Evidence.VERIFIED_D_PRESSES != 14) {
            throw new AssertionError("Evidence totals changed unexpectedly");
        }

        System.out.println("ALL MK15 SDK SELF-TESTS PASSED");
    }
}
