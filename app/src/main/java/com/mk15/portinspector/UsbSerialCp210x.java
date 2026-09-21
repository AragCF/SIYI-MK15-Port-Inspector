package com.mk15.portinspector;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class UsbSerialCp210x {
    public interface Listener {
        void onBytes(byte[] data, int len);
        void onInfo(String message);
        void onError(String message, Throwable error);
    }

    private static final int SILABS_VENDOR_ID = 0x10C4;
    private static final int REQTYPE_HOST_TO_INTERFACE = UsbConstants.USB_TYPE_VENDOR
            | UsbConstants.USB_RECIP_INTERFACE | UsbConstants.USB_DIR_OUT;
    private static final int CP210X_IFC_ENABLE = 0x00;
    private static final int CP210X_SET_LINE_CTL = 0x03;
    private static final int CP210X_SET_MHS = 0x07;
    private static final int CP210X_SET_BAUDRATE = 0x1E;
    private static final int UART_ENABLE = 0x0001;
    private static final int LINE_CTL_8N1 = 0x0800;
    private static final int MHS_DTR_RTS_ON = 0x0303;

    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private Thread readerThread;
    private volatile boolean running;

    public UsbSerialCp210x(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    public static List<UsbDevice> findSerialCandidates(UsbManager manager) {
        List<UsbDevice> cp210x = new ArrayList<>();
        List<UsbDevice> genericBulk = new ArrayList<>();
        Collection<UsbDevice> all = manager.getDeviceList().values();
        for (UsbDevice d : all) {
            if (hasBulkPair(d)) {
                if (d.getVendorId() == SILABS_VENDOR_ID) cp210x.add(d);
                else genericBulk.add(d);
            }
        }
        cp210x.addAll(genericBulk);
        return cp210x;
    }

    private static boolean hasBulkPair(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            boolean in = false;
            boolean out = false;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = true;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = true;
            }
            if (in && out) return true;
        }
        return false;
    }

    public synchronized void open(int baudRate) throws Exception {
        if (connection != null) return;
        if (!manager.hasPermission(device)) {
            throw new SecurityException("Нет разрешения Android на USB-устройство");
        }

        UsbInterface foundIntf = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
            if (in != null && out != null) {
                foundIntf = intf;
                foundIn = in;
                foundOut = out;
                break;
            }
        }
        if (foundIntf == null) throw new Exception("Не найден интерфейс USB с BULK IN/OUT");

        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) throw new Exception("UsbManager.openDevice() вернул null");
        if (!c.claimInterface(foundIntf, true)) {
            c.close();
            throw new Exception("Не удалось занять USB-интерфейс " + foundIntf.getId());
        }

        connection = c;
        usbInterface = foundIntf;
        endpointIn = foundIn;
        endpointOut = foundOut;

        if (device.getVendorId() == SILABS_VENDOR_ID) {
            configureCp210x(baudRate);
            info("CP210x настроен на " + baudRate + " бод, 8N1");
        } else {
            info("Устройство не CP210x (VID=" + hex4(device.getVendorId())
                    + "). Параметры UART не менялись; пробуем существующую настройку.");
        }

        running = true;
        readerThread = new Thread(this::readLoop, "mk15-usb-reader");
        readerThread.start();
    }

    private void configureCp210x(int baudRate) throws Exception {
        int iface = usbInterface.getId();
        checkControl(CP210X_IFC_ENABLE, UART_ENABLE, iface, null, "включение UART");
        checkControl(CP210X_SET_LINE_CTL, LINE_CTL_8N1, iface, null, "8N1");
        byte[] baud = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(baudRate).array();
        checkControl(CP210X_SET_BAUDRATE, 0, iface, baud, "скорость " + baudRate);
        // Поднять DTR/RTS. Для встроенного CP2102 это обычно безвредно и повышает совместимость.
        int r = connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, CP210X_SET_MHS,
                MHS_DTR_RTS_ON, iface, null, 0, 1000);
        if (r < 0) info("CP210x: не удалось установить DTR/RTS; продолжаем без этого шага");
    }

    private void checkControl(int request, int value, int index, byte[] data, String what) throws Exception {
        int len = data == null ? 0 : data.length;
        int r = connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, request, value, index,
                data, len, 1000);
        if (r < 0) throw new Exception("CP210x: ошибка настройки: " + what + " (" + r + ")");
    }

    private void readLoop() {
        byte[] buffer = new byte[512];
        try {
            while (running && connection != null) {
                int n = connection.bulkTransfer(endpointIn, buffer, buffer.length, 250);
                if (n > 0 && listener != null) {
                    byte[] copy = new byte[n];
                    System.arraycopy(buffer, 0, copy, 0, n);
                    listener.onBytes(copy, n);
                }
            }
        } catch (Throwable t) {
            if (running && listener != null) listener.onError("Ошибка чтения USB", t);
        }
    }

    public synchronized int write(byte[] bytes) throws Exception {
        if (connection == null || endpointOut == null) throw new Exception("USB COM не открыт");
        int n = connection.bulkTransfer(endpointOut, bytes, bytes.length, 1000);
        if (n != bytes.length) throw new Exception("USB: отправлено " + n + " из " + bytes.length + " байт");
        return n;
    }

    public synchronized boolean isOpen() {
        return connection != null;
    }

    public UsbDevice getDevice() {
        return device;
    }

    public synchronized void close() {
        running = false;
        Thread t = readerThread;
        readerThread = null;
        if (t != null) t.interrupt();
        if (connection != null) {
            try {
                if (usbInterface != null) connection.releaseInterface(usbInterface);
            } catch (Throwable ignored) {}
            try { connection.close(); } catch (Throwable ignored) {}
        }
        connection = null;
        usbInterface = null;
        endpointIn = null;
        endpointOut = null;
    }

    public static String describe(UsbDevice d) {
        return "VID=" + hex4(d.getVendorId()) + " PID=" + hex4(d.getProductId())
                + " name=" + d.getDeviceName() + " interfaces=" + d.getInterfaceCount();
    }

    private static String hex4(int v) {
        return String.format("%04X", v & 0xFFFF);
    }

    private void info(String s) {
        if (listener != null) listener.onInfo(s);
    }
}
