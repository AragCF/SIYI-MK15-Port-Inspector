import com.mk15.portinspector.SiyiProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProtocolSelfTest {
    private static void eq(String name, String actual, String expected) {
        if (!actual.equalsIgnoreCase(expected)) {
            throw new AssertionError(name + "\nexpected: " + expected + "\nactual:   " + actual);
        }
        System.out.println("OK: " + name + " = " + actual);
    }

    private static byte[] hex(String s) {
        String[] pp = s.trim().split("\\s+");
        byte[] out = new byte[pp.length];
        for (int i = 0; i < pp.length; i++) out[i] = (byte) Integer.parseInt(pp[i], 16);
        return out;
    }

    private static byte[] response(int ctrl, int seq, int cmd, byte[] data) {
        byte[] f = new byte[8 + data.length + 2];
        f[0] = 0x55; f[1] = 0x66; f[2] = (byte) ctrl;
        f[3] = (byte) (data.length & 0xff); f[4] = (byte) ((data.length >> 8) & 0xff);
        f[5] = (byte) (seq & 0xff); f[6] = (byte) ((seq >> 8) & 0xff);
        f[7] = (byte) cmd;
        System.arraycopy(data, 0, f, 8, data.length);
        int crc = SiyiProtocol.crc16(f, 0, 8 + data.length);
        f[8 + data.length] = (byte) (crc & 0xff);
        f[9 + data.length] = (byte) ((crc >> 8) & 0xff);
        return f;
    }

    public static void main(String[] args) {
        eq("0x42 4Hz request", SiyiProtocol.hex(SiyiProtocol.channelStreamRequest(2, 0)),
                "55 66 01 01 00 00 00 42 02 B5 C0");
        eq("0x42 OFF request", SiyiProtocol.hex(SiyiProtocol.channelStreamRequest(0, 0)),
                "55 66 01 01 00 00 00 42 00 F7 E0");
        eq("0x48 mapping request", SiyiProtocol.hex(SiyiProtocol.mappingRequest(0)),
                "55 66 01 00 00 00 00 48 89 1D");

        if (!"SA".equals(SiyiProtocol.physicalChannelName(5, 0))) {
            throw new AssertionError("type=5/entity=0 must decode as SA");
        }
        System.out.println("OK: type=5 entity_id=0 -> SA");

        List<SiyiProtocol.Frame> frames = new ArrayList<>();
        SiyiProtocol.Parser parser = new SiyiProtocol.Parser(new SiyiProtocol.FrameListener() {
            @Override public void onFrame(SiyiProtocol.Frame f) { frames.add(f); }
            @Override public void onBadCrc(byte[] candidate) { throw new AssertionError("Unexpected bad CRC: " + SiyiProtocol.hex(candidate)); }
        });

        // Official SIYI MK15 0x48 example response. This verifies response CRC, fragmentation and mapping order.
        byte[] mappingSample = hex("55 66 02 20 00 16 00 48 00 00 00 01 00 02 00 03 05 00 05 01 05 02 01 00 01 01 01 02 01 03 00 04 00 05 02 01 02 00 03 00 C1 28");
        parser.append(mappingSample, 11);
        parser.append(Arrays.copyOfRange(mappingSample, 11, mappingSample.length), mappingSample.length - 11);
        if (frames.size() != 1) throw new AssertionError("Expected 1 mapping frame, got " + frames.size());
        SiyiProtocol.Frame m = frames.get(0);
        if (m.cmdId != 0x48 || m.data.length != 32) throw new AssertionError("Wrong parsed 0x48 frame");
        int ch5Type = m.data[8] & 0xff;
        int ch5Entity = m.data[9] & 0xff;
        if (ch5Type != 5 || ch5Entity != 0) throw new AssertionError("Official CH5 mapping is not SA: " + ch5Type + "/" + ch5Entity);
        System.out.println("OK: official 0x48 response parsed; CH5 -> " + SiyiProtocol.physicalChannelName(ch5Type, ch5Entity));

        // Real MK15 1.3.1 capture from /dev/ttyHS0. The TTY had IUCLC enabled:
        // 0x55 ('U') became 0x75 ('u'), 0x48 ('H') became 0x68 ('h').
        // After restoring those bytes the recorded CRC 0xBB1D is exact.
        byte[] iuclcSample = hex("75 66 02 20 00 0C 00 68 00 00 00 01 00 02 00 03 05 00 05 01 05 02 01 00 01 01 01 02 01 03 00 04 00 05 02 01 00 04 01 01 1D BB");
        parser.append(iuclcSample, iuclcSample.length);
        if (frames.size() != 2) throw new AssertionError("Expected repaired real MK15 mapping frame");
        SiyiProtocol.Frame real = frames.get(1);
        if (!real.ttyCaseRepaired || real.cmdId != 0x48) {
            throw new AssertionError("Real MK15 IUCLC frame was not repaired");
        }
        if ((real.data[18] & 0xFF) != 1 || (real.data[19] & 0xFF) != 2) {
            throw new AssertionError("Real MK15 CH10 must map to C");
        }
        if ((real.data[20] & 0xFF) != 1 || (real.data[21] & 0xFF) != 3) {
            throw new AssertionError("Real MK15 CH11 must map to D");
        }
        System.out.println("OK: real MK15 IUCLC-damaged 0x48 repaired; CH10=C, CH11=D");

        // Synthetic, CRC-correct 0x42 response with 16 channels. Live MK15 values are validated on hardware later.
        int[] values = {1500, 1500, 1500, 1500, 1050, 1500, 1950, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500};
        byte[] chData = new byte[32];
        for (int i = 0; i < values.length; i++) {
            chData[i * 2] = (byte) (values[i] & 0xff);
            chData[i * 2 + 1] = (byte) ((values[i] >> 8) & 0xff);
        }
        byte[] chFrame = response(0, 153, 0x42, chData);
        parser.append(chFrame, chFrame.length);
        if (frames.size() != 3) throw new AssertionError("Expected channel frame");
        SiyiProtocol.Frame c = frames.get(2);
        if (SiyiProtocol.u16le(c.data, 8) != 1050) throw new AssertionError("CH5 decode failed");
        System.out.println("OK: 0x42 parser decoded 16 channels; CH5=1050");

        System.out.println("ALL PROTOCOL SELF-TESTS PASSED");
    }
}
