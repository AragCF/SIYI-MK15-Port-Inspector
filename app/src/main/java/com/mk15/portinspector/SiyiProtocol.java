package com.mk15.portinspector;

import java.util.Arrays;

public final class SiyiProtocol {
    public static final int CMD_HARDWARE_ID = 0x40;
    public static final int CMD_CHANNEL_DATA = 0x42;
    public static final int CMD_DATALINK_STATUS = 0x43;
    public static final int CMD_IMAGE_LINK_STATUS = 0x44;
    public static final int CMD_FIRMWARE_VERSION = 0x47;
    public static final int CMD_ALL_CHANNEL_MAPPINGS = 0x48;

    private SiyiProtocol() {}

    public static byte[] request(int cmdId, byte[] data, int seq) {
        if (data == null) data = new byte[0];
        int dataLen = data.length;
        byte[] frame = new byte[8 + dataLen + 2];
        frame[0] = 0x55;
        frame[1] = 0x66;
        frame[2] = 0x01; // need_ack
        frame[3] = (byte) (dataLen & 0xFF);
        frame[4] = (byte) ((dataLen >> 8) & 0xFF);
        frame[5] = (byte) (seq & 0xFF);
        frame[6] = (byte) ((seq >> 8) & 0xFF);
        frame[7] = (byte) (cmdId & 0xFF);
        System.arraycopy(data, 0, frame, 8, dataLen);
        int crc = crc16(frame, 0, 8 + dataLen);
        frame[8 + dataLen] = (byte) (crc & 0xFF);
        frame[9 + dataLen] = (byte) ((crc >> 8) & 0xFF);
        return frame;
    }

    public static byte[] channelStreamRequest(int frequencyCode, int seq) {
        return request(CMD_CHANNEL_DATA, new byte[]{(byte) frequencyCode}, seq);
    }

    public static byte[] mappingRequest(int seq) {
        return request(CMD_ALL_CHANNEL_MAPPINGS, new byte[0], seq);
    }

    public static int crc16(byte[] data, int offset, int len) {
        int crc = 0;
        for (int i = offset; i < offset + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }

    public static int u16le(byte[] data, int p) {
        return (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8);
    }

    public static long u32le(byte[] data, int p) {
        return ((long) data[p] & 0xFFL)
                | (((long) data[p + 1] & 0xFFL) << 8)
                | (((long) data[p + 2] & 0xFFL) << 16)
                | (((long) data[p + 3] & 0xFFL) << 24);
    }

    public static String physicalChannelName(int type, int entityId) {
        if (type == 0) {
            switch (entityId) {
                case 0: return "J1";
                case 1: return "J2";
                case 2: return "J3";
                case 3: return "J4";
                case 4: return "LD";
                case 5: return "RD";
                default: return "Аналоговый #" + entityId;
            }
        }
        if (type == 5) {
            switch (entityId) {
                case 0: return "SA";
                case 1: return "SB";
                case 2: return "SC";
                default: return "3-поз. переключатель #" + entityId;
            }
        }
        if (type == 1) {
            switch (entityId) {
                case 0: return "A";
                case 1: return "B";
                case 2: return "C";
                case 3: return "D";
                default: return "Кнопка #" + entityId;
            }
        }
        if (type == 2) return "Виртуальный";
        if (type == 3) return "Не назначен";
        return "Тип " + type + ", id " + entityId;
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    public static final class Frame {
        public final int ctrl;
        public final int seq;
        public final int cmdId;
        public final byte[] data;

        Frame(int ctrl, int seq, int cmdId, byte[] data) {
            this.ctrl = ctrl;
            this.seq = seq;
            this.cmdId = cmdId;
            this.data = data;
        }
    }

    public interface FrameListener {
        void onFrame(Frame frame);
        void onBadCrc(byte[] candidate);
    }

    public static final class Parser {
        private byte[] buffer = new byte[4096];
        private int size = 0;
        private final FrameListener listener;

        public Parser(FrameListener listener) {
            this.listener = listener;
        }

        public synchronized void append(byte[] bytes, int len) {
            if (bytes == null || len <= 0) return;
            ensure(size + len);
            System.arraycopy(bytes, 0, buffer, size, len);
            size += len;
            parse();
        }

        private void parse() {
            while (size >= 10) {
                int start = findHeader();
                if (start < 0) {
                    // Keep one possible leading 0x55 for the next chunk.
                    if ((buffer[size - 1] & 0xFF) == 0x55) {
                        buffer[0] = buffer[size - 1];
                        size = 1;
                    } else {
                        size = 0;
                    }
                    return;
                }
                if (start > 0) discard(start);
                if (size < 10) return;

                int dataLen = u16le(buffer, 3);
                if (dataLen < 0 || dataLen > 2048) {
                    discard(1);
                    continue;
                }
                int frameLen = 8 + dataLen + 2;
                if (size < frameLen) return;

                int expected = u16le(buffer, 8 + dataLen);
                int actual = crc16(buffer, 0, 8 + dataLen);
                if (expected != actual) {
                    if (listener != null) {
                        listener.onBadCrc(Arrays.copyOfRange(buffer, 0, frameLen));
                    }
                    discard(1);
                    continue;
                }

                int ctrl = buffer[2] & 0xFF;
                int seq = u16le(buffer, 5);
                int cmd = buffer[7] & 0xFF;
                byte[] data = Arrays.copyOfRange(buffer, 8, 8 + dataLen);
                if (listener != null) listener.onFrame(new Frame(ctrl, seq, cmd, data));
                discard(frameLen);
            }
        }

        private int findHeader() {
            for (int i = 0; i < size - 1; i++) {
                if ((buffer[i] & 0xFF) == 0x55 && (buffer[i + 1] & 0xFF) == 0x66) return i;
            }
            return -1;
        }

        private void discard(int count) {
            if (count <= 0) return;
            if (count >= size) {
                size = 0;
                return;
            }
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }

        private void ensure(int needed) {
            if (needed <= buffer.length) return;
            int newSize = buffer.length;
            while (newSize < needed) newSize *= 2;
            buffer = Arrays.copyOf(buffer, newSize);
        }
    }
}
