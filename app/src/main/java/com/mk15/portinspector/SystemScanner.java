package com.mk15.portinspector;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SystemScanner {
    private SystemScanner() {}

    public static String collect(Context context) {
        StringBuilder out = new StringBuilder(16_384);
        out.append("=== MK15 Port Inspector: снимок состояния ===\n");
        out.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("Продукт: ").append(Build.PRODUCT).append(" / ").append(Build.DEVICE).append('\n');
        out.append("Аппаратура: ").append(Build.HARDWARE).append(" / ").append(Build.BOARD).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");

        appendUsb(context, out);
        appendInputDevices(context, out);
        appendCandidateDeviceNodes(out);
        appendSysTtys(out);
        appendReadableFile(out, "/proc/tty/drivers", 12_000);
        appendReadableFile(out, "/proc/bus/input/devices", 20_000);
        appendReadableFile(out, "/proc/net/unix", 16_000);
        appendReadableFile(out, "/proc/net/tcp", 12_000);
        appendReadableFile(out, "/proc/net/udp", 12_000);
        appendNetworks(out);
        appendSpecialPaths(out);
        return out.toString();
    }

    private static void appendUsb(Context context, StringBuilder out) {
        out.append("=== USB ===\n");
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            out.append("UsbManager недоступен\n\n");
            return;
        }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices.isEmpty()) out.append("USB-устройства Android Host не видит.\n");
        for (UsbDevice d : devices.values()) {
            out.append("- ").append(UsbSerialCp210x.describe(d))
                    .append(" permission=").append(manager.hasPermission(d)).append('\n');
            try {
                out.append("  manufacturer=").append(d.getManufacturerName())
                        .append(" product=").append(d.getProductName())
                        .append(" serial=").append(d.getSerialNumber()).append('\n');
            } catch (Throwable t) {
                out.append("  имена/серийный номер недоступны без разрешения USB\n");
            }
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface in = d.getInterface(i);
                out.append("  interface ").append(in.getId())
                        .append(" class=").append(in.getInterfaceClass())
                        .append(" subclass=").append(in.getInterfaceSubclass())
                        .append(" protocol=").append(in.getInterfaceProtocol())
                        .append(" endpoints=").append(in.getEndpointCount()).append('\n');
                for (int e = 0; e < in.getEndpointCount(); e++) {
                    UsbEndpoint ep = in.getEndpoint(e);
                    out.append("    ep 0x").append(String.format(Locale.US, "%02X", ep.getAddress()))
                            .append(" type=").append(ep.getType())
                            .append(" dir=").append(ep.getDirection() == 0x80 ? "IN" : "OUT")
                            .append(" maxPacket=").append(ep.getMaxPacketSize()).append('\n');
                }
            }
        }
        out.append('\n');
    }

    private static void appendInputDevices(Context context, StringBuilder out) {
        out.append("=== Android / Linux input ===\n");
        int[] ids = InputDevice.getDeviceIds();
        if (ids.length == 0) out.append("InputDevice: устройств нет\n");
        for (int id : ids) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            out.append("- id=").append(id)
                    .append(" name=").append(d.getName())
                    .append(" descriptor=").append(d.getDescriptor())
                    .append(" sources=0x").append(Integer.toHexString(d.getSources()))
                    .append(" external=").append(d.isExternal())
                    .append('\n');
            for (InputDevice.MotionRange r : d.getMotionRanges()) {
                out.append("  axis=").append(r.getAxis())
                        .append(" source=0x").append(Integer.toHexString(r.getSource()))
                        .append(" min=").append(r.getMin())
                        .append(" max=").append(r.getMax())
                        .append(" flat=").append(r.getFlat()).append('\n');
            }
        }
        out.append('\n');
    }

    private static void appendCandidateDeviceNodes(StringBuilder out) {
        out.append("=== /dev: кандидаты на порты/ввод ===\n");
        File dev = new File("/dev");
        File[] files = dev.listFiles();
        if (files == null) {
            out.append("Каталог /dev недоступен приложению.\n\n");
            return;
        }
        List<File> list = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.startsWith("tty") || n.contains("serial") || n.contains("uart")
                    || n.contains("usb") || n.contains("hid") || n.contains("input")
                    || n.startsWith("smd") || n.startsWith("diag") || n.startsWith("gpio")
                    || n.startsWith("i2c") || n.startsWith("spi")) {
                list.add(f);
            }
        }
        Collections.sort(list, Comparator.comparing(File::getName));
        for (File f : list) {
            out.append("- ").append(f.getAbsolutePath())
                    .append(" r=").append(f.canRead())
                    .append(" w=").append(f.canWrite())
                    .append(" x=").append(f.canExecute())
                    .append(" len=").append(f.length()).append('\n');
        }
        if (list.isEmpty()) out.append("Подходящих узлов не видно из песочницы приложения.\n");
        out.append('\n');
    }

    private static void appendSysTtys(StringBuilder out) {
        out.append("=== /sys/class/tty ===\n");
        File dir = new File("/sys/class/tty");
        File[] files = dir.listFiles();
        if (files == null) {
            out.append("Недоступно\n\n");
            return;
        }
        List<String> names = new ArrayList<>();
        for (File f : files) names.add(f.getName());
        Collections.sort(names);
        for (String n : names) out.append(n).append(' ');
        out.append("\n\n");
    }

    private static void appendReadableFile(StringBuilder out, String path, int maxChars) {
        out.append("=== ").append(path).append(" ===\n");
        File f = new File(path);
        if (!f.canRead()) {
            out.append("Недоступно для чтения\n\n");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int total = 0;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                total += line.length() + 1;
                if (total >= maxChars) {
                    out.append("...[обрезано]...\n");
                    break;
                }
            }
        } catch (Throwable t) {
            out.append("Ошибка: ").append(t).append('\n');
        }
        out.append('\n');
    }

    private static void appendNetworks(StringBuilder out) {
        out.append("=== Сетевые интерфейсы ===\n");
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) {
                out.append("Нет данных\n\n");
                return;
            }
            List<NetworkInterface> list = Collections.list(all);
            Collections.sort(list, Comparator.comparing(NetworkInterface::getName));
            for (NetworkInterface n : list) {
                out.append("- ").append(n.getName())
                        .append(" up=").append(n.isUp())
                        .append(" loopback=").append(n.isLoopback())
                        .append(" mtu=").append(n.getMTU()).append('\n');
                Enumeration<InetAddress> aa = n.getInetAddresses();
                while (aa.hasMoreElements()) out.append("  ").append(aa.nextElement().getHostAddress()).append('\n');
            }
        } catch (Throwable t) {
            out.append("Ошибка: ").append(t).append('\n');
        }
        out.append('\n');
    }

    private static void appendSpecialPaths(StringBuilder out) {
        out.append("=== Ключевые пути SIYI/Android ===\n");
        String[] paths = {
                "/dev/ttyHS0",
                "/sys/class/gpio",
                "/sys/class/input",
                "/sys/class/usb_device",
                "/proc/net/udp",
                "/proc/net/tcp"
        };
        for (String p : paths) {
            File f = new File(p);
            out.append(p)
                    .append(" exists=").append(f.exists())
                    .append(" r=").append(f.canRead())
                    .append(" w=").append(f.canWrite())
                    .append(" dir=").append(f.isDirectory()).append('\n');
        }
        out.append('\n');
    }
}
