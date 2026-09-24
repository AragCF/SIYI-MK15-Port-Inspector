package com.mk15.sdk;

public final class Mk15Evidence {
    public static final String SDK_VERSION = "1.0.0";
    public static final String VERIFIED_DEVICE = "SIYI MK15";
    public static final String UART_PATH = "/dev/ttyHS0";
    public static final int UART_BAUD = 115200;

    public static final int VERIFIED_C_CHANNEL = 10;
    public static final int VERIFIED_D_CHANNEL = 11;
    public static final int VERIFIED_RELEASED_VALUE = 1050;
    public static final int VERIFIED_PRESSED_VALUE = 1950;

    public static final int RELEASED_MAX = 1300;
    public static final int PRESSED_MIN = 1700;

    public static final int VERIFIED_CHANNEL_FRAMES = 391;
    public static final int VERIFIED_C_PRESSES = 14;
    public static final int VERIFIED_D_PRESSES = 14;
    public static final int VERIFIED_LABELLED_ACTIONS = 28;

    public static final String REPORT_1 = "MK15_Report_20260924_134701_v1_4_0.zip";
    public static final String REPORT_1_SHA256 =
            "F33D11B3880FC6358E9D09F7B3672B0B3BD4F3C80D3FA8BDB073F140D2DDB6D2";
    public static final String REPORT_2 = "MK15_Report_20260924_210500_v1_4_0.zip";
    public static final String REPORT_2_SHA256 =
            "4189C17C22E496F6B55229B86AFED804A948B01A3E0CF26C19BAB9333C6D54E9";

    private Mk15Evidence() {}

    public static boolean isReleased(int rawValue) {
        return rawValue <= RELEASED_MAX;
    }

    public static boolean isPressed(int rawValue) {
        return rawValue >= PRESSED_MIN;
    }
}
