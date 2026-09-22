package com.mk15.portinspector;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Диагностическое приложение для SIYI MK15.
 *
 * Основной безопасный путь чтения каналов: встроенный CP2102 (режим SIYI TX -> USB COM)
 * и публичный SIYI Datalink SDK. Приложение не требует root и не изменяет mapping.
 */
public final class MainActivity extends Activity implements SiyiProtocol.FrameListener {
    private static final String ACTION_USB_PERMISSION = "com.mk15.portinspector.USB_PERMISSION";
    private static final int CHANNEL_COUNT = 16;
    private static final int LOG_LIMIT = 64_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final SiyiProtocol.Parser parser = new SiyiProtocol.Parser(this);

    private UsbManager usbManager;
    private UsbSerialCp210x serial;
    private UsbDevice pendingUsbDevice;

    private final int[] mappingType = new int[CHANNEL_COUNT];
    private final int[] mappingEntity = new int[CHANNEL_COUNT];
    private final int[] channelValue = new int[CHANNEL_COUNT];
    private final TextView[] channelRows = new TextView[CHANNEL_COUNT];
    private boolean mappingReceived;
    private volatile boolean streamEnabled;
    private int saChannelIndex = 4; // только как известное заводское значение до ответа 0x48

    private TextView statusText;
    private TextView saText;
    private TextView logText;
    private TextView scanText;
    private EditText baudEdit;

    private final StringBuilder sessionLog = new StringBuilder(16_384);
    private File runtimeLogFile;
    private long usbRxBytes;
    private int usbRxChunks;
    private int validSiyiFrames;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                appendLog("USB: разрешение не выдано.");
                setStatus("USB COM: нет разрешения");
                return;
            }
            appendLog("USB: разрешение получено для " + UsbSerialCp210x.describe(device));
            pendingUsbDevice = null;
            openUsbDevice(device);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Arrays.fill(mappingType, -1);
        Arrays.fill(mappingEntity, -1);
        Arrays.fill(channelValue, -1);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        setContentView(buildUi());
        initRuntimeLog();
        applyDefaultMappingPreview();
        appendLog("MK15 Port Inspector 1.0.1 запущен.");
        appendLog("Важно: поток 0x42 использует тот же канал связи, что телеметрия. Проверять только на столе, не в полёте.");
        scanPorts();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        TextView title = text("SIYI MK15 — поиск портов и переключателя SA", 22, true);
        root.addView(title, lpMatchWrap());

        TextView warning = text(
                "Диагностика на столе: команда чтения RC-каналов 0x42 может мешать телеметрии. " +
                        "Перед запуском закройте QGroundControl/другую программу, занявшую USB COM. " +
                        "Приложение mapping не изменяет.", 14, false);
        warning.setTextColor(Color.rgb(150, 55, 0));
        root.addView(warning, lpMatchWrap());

        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        statusLine.setPadding(0, dp(5), 0, dp(4));

        statusText = text("USB COM: не подключён", 15, true);
        statusLine.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView baudLabel = text("Скорость:", 14, false);
        statusLine.addView(baudLabel);
        baudEdit = new EditText(this);
        baudEdit.setSingleLine(true);
        baudEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        baudEdit.setText("57600");
        baudEdit.setSelectAllOnFocus(true);
        statusLine.addView(baudEdit, new LinearLayout.LayoutParams(dp(125), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(statusLine, lpMatchWrap());

        HorizontalScrollView commandScroller = new HorizontalScrollView(this);
        commandScroller.setHorizontalScrollBarEnabled(true);
        LinearLayout commands = new LinearLayout(this);
        commands.setOrientation(LinearLayout.HORIZONTAL);

        commands.addView(button("Сканировать порты", v -> scanPorts()));
        commands.addView(button("Подключить USB COM", v -> connectUsb()));
        commands.addView(button("Mapping 0x48", v -> requestMapping()));
        commands.addView(button("RC 4 Гц 0x42", v -> startRcStream()));
        commands.addView(button("СТОП RC", v -> stopRcStream(true)));
        commands.addView(button("Версии 0x40/0x47", v -> requestInfo()));
        commands.addView(button("Сохранить отчёт", v -> saveReport()));
        commands.addView(button("Очистить журнал", v -> clearLog()));
        commandScroller.addView(commands);
        root.addView(commandScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        saText = text("SA: заводской mapping = CH5; живые данные ещё не получены", 20, true);
        saText.setPadding(dp(8), dp(5), dp(8), dp(5));
        saText.setBackgroundColor(Color.rgb(255, 248, 225));
        root.addView(saText, lpMatchWrap());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView channelsScroll = new ScrollView(this);
        LinearLayout channelBox = new LinearLayout(this);
        channelBox.setOrientation(LinearLayout.VERTICAL);
        TextView channelHeader = text("16 коммуникационных каналов", 16, true);
        channelBox.addView(channelHeader, lpMatchWrap());
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            TextView row = text("CH" + String.format(Locale.US, "%02d", i + 1) + "  —", 15, false);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.setBackgroundColor((i % 2 == 0) ? Color.WHITE : Color.rgb(238, 238, 238));
            channelRows[i] = row;
            channelBox.addView(row, lpMatchWrap());
        }
        channelsScroll.addView(channelBox);
        content.addView(channelsScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(10), 0, 0, 0);

        TextView scanHeader = text("Видимые системе порты / устройства", 16, true);
        right.addView(scanHeader, lpMatchWrap());
        ScrollView scanScroll = new ScrollView(this);
        scanText = text("Сканирование...", 12, false);
        scanText.setTextIsSelectable(true);
        scanText.setTypeface(android.graphics.Typeface.MONOSPACE);
        scanScroll.addView(scanText);
        right.addView(scanScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.54f));

        TextView logHeader = text("Журнал протокола / Android input", 16, true);
        right.addView(logHeader, lpMatchWrap());
        ScrollView logScroll = new ScrollView(this);
        logText = text("", 12, false);
        logText.setTextIsSelectable(true);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText);
        right.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.46f));

        content.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.60f));
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(30, 30, 30));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    private void applyDefaultMappingPreview() {
        String[] defaults = {"J1", "J2", "J3", "J4", "SA", "SB", "SC", "A", "B", "C", "D", "LD", "RD", "—", "—", "—"};
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            updateChannelRow(i, defaults[i] + " (заводской)", -1, i == 4);
        }
    }

    private void scanPorts() {
        if (scanText != null) scanText.setText("Сканирование...\n");
        appendLog("Сканирование Android input, USB, /dev, /sys/class/tty и сетевых интерфейсов...");
        worker.submit(() -> {
            String report;
            try {
                report = SystemScanner.collect(getApplicationContext());
            } catch (Throwable t) {
                report = "Ошибка сканирования: " + stackSummary(t);
            }
            final String finalReport = report;
            runOnUiThread(() -> {
                if (scanText != null) scanText.setText(finalReport);
                appendLog("Сканирование завершено. Видимые интерфейсы показаны справа.");
            });
        });
    }

    private void connectUsb() {
        if (usbManager == null) {
            setStatus("UsbManager недоступен");
            appendLog("UsbManager недоступен.");
            return;
        }
        if (serial != null && serial.isOpen()) {
            appendLog("USB COM уже открыт: " + UsbSerialCp210x.describe(serial.getDevice()));
            return;
        }
        List<UsbDevice> candidates = UsbSerialCp210x.findSerialCandidates(usbManager);
        if (candidates.isEmpty()) {
            setStatus("USB COM: устройство с BULK IN/OUT не найдено");
            appendLog("USB: не найден ни CP210x, ни другой интерфейс с парой BULK IN/OUT. Нажмите «Сканировать порты» и сохраните отчёт.");
            return;
        }

        UsbDevice selected = candidates.get(0); // CP210x всегда сортируется первым
        appendLog("USB: выбран кандидат " + UsbSerialCp210x.describe(selected));
        if (!usbManager.hasPermission(selected)) {
            pendingUsbDevice = selected;
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
            permissionIntent.setPackage(getPackageName());
            int flags = 0;
            if (Build.VERSION.SDK_INT >= 31) flags = PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags);
            setStatus("USB COM: ожидается разрешение Android");
            usbManager.requestPermission(selected, pi);
            return;
        }
        openUsbDevice(selected);
    }

    private void openUsbDevice(UsbDevice device) {
        int baud;
        try {
            baud = Integer.parseInt(baudEdit.getText().toString().trim());
            if (baud < 300 || baud > 3_000_000) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Введите корректную скорость порта, например 57600", Toast.LENGTH_LONG).show();
            return;
        }

        final int selectedBaud = baud;
        worker.submit(() -> {
            closeSerial();
            UsbSerialCp210x candidate = new UsbSerialCp210x(usbManager, device, new UsbSerialCp210x.Listener() {
                @Override
                public void onBytes(byte[] data, int len) {
                    usbRxBytes += len;
                    usbRxChunks++;
                    if (usbRxChunks <= 20 || (usbRxChunks % 100) == 0) {
                        byte[] shown = data;
                        if (len > 96) shown = Arrays.copyOf(data, 96);
                        appendLog("USB RX chunk=" + usbRxChunks + " len=" + len
                                + " total=" + usbRxBytes + " hex=" + SiyiProtocol.hex(shown)
                                + (len > shown.length ? " ..." : ""));
                    }
                    parser.append(data, len);
                }

                @Override
                public void onInfo(String message) {
                    appendLog("USB: " + message);
                }

                @Override
                public void onError(String message, Throwable error) {
                    appendLog("USB: " + message + (error == null ? "" : " — " + error));
                    runOnUiThread(() -> setStatus("USB COM: ошибка чтения"));
                }
            });
            try {
                candidate.open(selectedBaud);
                serial = candidate;
                appendLog("USB COM открыт: " + UsbSerialCp210x.describe(device) + ", " + selectedBaud + " бод.");
                runOnUiThread(() -> setStatus("USB COM: подключён, " + selectedBaud + " бод"));
                // Сразу читаем фактический mapping и версии. Mapping ничего не изменяет.
                sendFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 запрос mapping");
                sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_HARDWARE_ID, new byte[0], nextSeq()), "0x40 hardware ID");
                sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_FIRMWARE_VERSION, new byte[0], nextSeq()), "0x47 версии");
            } catch (Throwable t) {
                candidate.close();
                serial = null;
                appendLog("USB COM открыть не удалось: " + stackSummary(t));
                runOnUiThread(() -> setStatus("USB COM: подключение не удалось"));
            }
        });
    }

    private void requestMapping() {
        if (!ensureSerial()) return;
        worker.submit(() -> sendFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 запрос всех mapping"));
    }

    private void requestInfo() {
        if (!ensureSerial()) return;
        worker.submit(() -> {
            sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_HARDWARE_ID, new byte[0], nextSeq()), "0x40 hardware ID");
            sleepQuiet(80);
            sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_FIRMWARE_VERSION, new byte[0], nextSeq()), "0x47 версии прошивок");
            sleepQuiet(80);
            sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_DATALINK_STATUS, new byte[0], nextSeq()), "0x43 состояние datalink");
            sleepQuiet(80);
            sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_IMAGE_LINK_STATUS, new byte[0], nextSeq()), "0x44 состояние видеолинии");
        });
    }

    private void startRcStream() {
        if (!ensureSerial()) return;
        worker.submit(() -> {
            // SIYI для этого класса протокола рекомендует подряд повторять команду запуска/остановки.
            int seq = nextSeq();
            byte[] frame = SiyiProtocol.channelStreamRequest(2, seq); // 4 Гц
            appendLog("RC: включаем поток 4 Гц командой 0x42 (три одинаковых отправки).\nTX: " + SiyiProtocol.hex(frame));
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                if (!sendFrame(frame, null)) ok = false;
                sleepQuiet(60);
            }
            streamEnabled = ok;
            if (ok) {
                runOnUiThread(() -> setStatus("USB COM: RC-поток 4 Гц включён"));
                // Mapping нужен, чтобы найти SA даже если пользователь переназначил каналы.
                sendFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 контроль mapping после запуска");
            }
        });
    }

    private void stopRcStream(boolean userInitiated) {
        if (serial == null || !serial.isOpen()) {
            streamEnabled = false;
            if (userInitiated) appendLog("RC: поток уже не подключён.");
            return;
        }
        worker.submit(() -> {
            int seq = nextSeq();
            byte[] frame = SiyiProtocol.channelStreamRequest(0, seq);
            if (userInitiated) appendLog("RC: выключаем поток 0x42 (три одинаковых отправки).\nTX: " + SiyiProtocol.hex(frame));
            for (int i = 0; i < 3; i++) {
                sendFrame(frame, null);
                sleepQuiet(60);
            }
            streamEnabled = false;
            runOnUiThread(() -> setStatus(serial != null && serial.isOpen()
                    ? "USB COM: подключён; RC-поток выключен"
                    : "USB COM: не подключён"));
        });
    }

    private boolean ensureSerial() {
        if (serial != null && serial.isOpen()) return true;
        Toast.makeText(this, "Сначала нажмите «Подключить USB COM»", Toast.LENGTH_LONG).show();
        appendLog("Команда не отправлена: USB COM не открыт.");
        return false;
    }

    private boolean sendFrame(byte[] frame, String label) {
        UsbSerialCp210x s = serial;
        if (s == null || !s.isOpen()) {
            if (label != null) appendLog(label + ": USB COM не открыт.");
            return false;
        }
        try {
            s.write(frame);
            if (label != null) appendLog("TX " + label + ": " + SiyiProtocol.hex(frame));
            return true;
        } catch (Throwable t) {
            appendLog((label == null ? "TX" : label) + ": ошибка отправки — " + stackSummary(t));
            return false;
        }
    }

    @Override
    public void onFrame(SiyiProtocol.Frame frame) {
        validSiyiFrames++;
        if (validSiyiFrames <= 20 || (validSiyiFrames % 100) == 0) {
            appendLog("SIYI frame #" + validSiyiFrames + " cmd=0x"
                    + String.format(Locale.US, "%02X", frame.cmdId)
                    + " seq=" + frame.seq + " dataLen=" + frame.data.length);
        }
        switch (frame.cmdId) {
            case SiyiProtocol.CMD_CHANNEL_DATA:
                handleChannelFrame(frame.data);
                break;
            case SiyiProtocol.CMD_ALL_CHANNEL_MAPPINGS:
                handleMappingFrame(frame.data);
                break;
            case SiyiProtocol.CMD_HARDWARE_ID:
                appendLog("RX 0x40 hardware ID: " + decodePrintableOrHex(frame.data));
                break;
            case SiyiProtocol.CMD_FIRMWARE_VERSION:
                appendLog("RX 0x47 версии: " + decodeFirmwareVersions(frame.data));
                break;
            case SiyiProtocol.CMD_DATALINK_STATUS:
                appendLog("RX 0x43 datalink: " + SiyiProtocol.hex(frame.data));
                break;
            case SiyiProtocol.CMD_IMAGE_LINK_STATUS:
                appendLog("RX 0x44 image link: " + SiyiProtocol.hex(frame.data));
                break;
            default:
                appendLog("RX cmd=0x" + String.format(Locale.US, "%02X", frame.cmdId)
                        + " seq=" + frame.seq + " data=" + SiyiProtocol.hex(frame.data));
        }
    }

    @Override
    public void onBadCrc(byte[] candidate) {
        appendLog("RX: CRC не совпал, отброшен кадр: " + SiyiProtocol.hex(candidate));
    }

    private void handleChannelFrame(byte[] data) {
        if (data.length < 32) {
            appendLog("RX 0x42: короткий ответ " + data.length + " байт: " + SiyiProtocol.hex(data));
            return;
        }
        int[] values = new int[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) values[i] = SiyiProtocol.u16le(data, i * 2);
        System.arraycopy(values, 0, channelValue, 0, CHANNEL_COUNT);

        runOnUiThread(() -> {
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                String name = mappedName(i);
                boolean isSa = i == saChannelIndex;
                updateChannelRow(i, name, values[i], isSa);
            }
            int saValue = saChannelIndex >= 0 && saChannelIndex < CHANNEL_COUNT ? values[saChannelIndex] : -1;
            String mappingNote = mappingReceived ? "по фактическому mapping 0x48" : "по заводскому CH5; mapping 0x48 ещё не подтверждён";
            saText.setText("SA → CH" + (saChannelIndex + 1) + " = " + saValue + "  (" + mappingNote + ")");
            saText.setBackgroundColor(Color.rgb(200, 230, 201));
        });
    }

    private void handleMappingFrame(byte[] data) {
        if (data.length < 32) {
            appendLog("RX 0x48: короткий mapping " + data.length + " байт: " + SiyiProtocol.hex(data));
            return;
        }
        int foundSa = -1;
        StringBuilder decoded = new StringBuilder("RX 0x48 mapping:");
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            int type = data[i * 2] & 0xFF;
            int entity = data[i * 2 + 1] & 0xFF;
            mappingType[i] = type;
            mappingEntity[i] = entity;
            String name = SiyiProtocol.physicalChannelName(type, entity);
            decoded.append(" CH").append(i + 1).append('=').append(name).append(';');
            if (type == 5 && entity == 0) foundSa = i;
        }
        mappingReceived = true;
        if (foundSa >= 0) saChannelIndex = foundSa;
        appendLog(decoded.toString());
        final int finalFoundSa = foundSa;
        runOnUiThread(() -> {
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                updateChannelRow(i, mappedName(i), channelValue[i], i == saChannelIndex);
            }
            if (finalFoundSa >= 0) {
                saText.setText("SA найден в mapping: физический type=5, entity_id=0 → CH" + (finalFoundSa + 1)
                        + (channelValue[finalFoundSa] >= 0 ? " = " + channelValue[finalFoundSa] : ""));
                saText.setBackgroundColor(Color.rgb(200, 230, 201));
            } else {
                saText.setText("В ответе 0x48 mapping SA (type=5, entity_id=0) не найден. Смотрите журнал.");
                saText.setBackgroundColor(Color.rgb(255, 205, 210));
            }
        });
    }

    private String mappedName(int index) {
        if (mappingReceived && mappingType[index] >= 0) {
            return SiyiProtocol.physicalChannelName(mappingType[index], mappingEntity[index]);
        }
        String[] defaults = {"J1", "J2", "J3", "J4", "SA", "SB", "SC", "A", "B", "C", "D", "LD", "RD", "—", "—", "—"};
        return defaults[index] + " (заводской)";
    }

    private void updateChannelRow(int index, String name, int value, boolean isSa) {
        if (channelRows[index] == null) return;
        String valueText = value >= 0 ? String.valueOf(value) : "—";
        channelRows[index].setText(String.format(Locale.US, "CH%02d  %-18s  %s", index + 1, name, valueText));
        if (isSa) {
            channelRows[index].setBackgroundColor(Color.rgb(200, 230, 201));
            channelRows[index].setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            channelRows[index].setBackgroundColor((index % 2 == 0) ? Color.WHITE : Color.rgb(238, 238, 238));
            channelRows[index].setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private String decodePrintableOrHex(byte[] data) {
        boolean printable = data.length > 0;
        for (byte b : data) {
            int v = b & 0xFF;
            if (v != 0 && (v < 32 || v > 126)) {
                printable = false;
                break;
            }
        }
        if (printable) {
            String s = new String(data).replace("\u0000", "").trim();
            if (!s.isEmpty()) return s + " [" + SiyiProtocol.hex(data) + "]";
        }
        return SiyiProtocol.hex(data);
    }

    private String decodeFirmwareVersions(byte[] data) {
        if (data.length < 16) return SiyiProtocol.hex(data);
        String[] names = {"RC", "RF", "Ground FPV", "Sky FPV"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("; ");
            int p = i * 4;
            int major = data[p + 2] & 0xFF;
            int minor = data[p + 1] & 0xFF;
            int patch = data[p] & 0xFF;
            int product = data[p + 3] & 0xFF;
            sb.append(names[i]).append('=').append(major).append('.').append(minor).append('.').append(patch)
                    .append(" product=0x").append(String.format(Locale.US, "%02X", product));
        }
        return sb.toString();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            appendLog("Android KeyEvent: action=" + event.getAction()
                    + " keyCode=" + event.getKeyCode()
                    + " scanCode=" + event.getScanCode()
                    + " deviceId=" + event.getDeviceId());
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            InputDevice device = event.getDevice();
            StringBuilder sb = new StringBuilder("Android MotionEvent: dev=")
                    .append(event.getDeviceId()).append(" src=0x").append(Integer.toHexString(source));
            if (device != null) {
                for (InputDevice.MotionRange r : device.getMotionRanges()) {
                    if ((r.getSource() & source) == 0) continue;
                    float value = event.getAxisValue(r.getAxis());
                    if (Math.abs(value) > 0.0001f) {
                        sb.append(" axis").append(r.getAxis()).append('=').append(String.format(Locale.US, "%.3f", value));
                    }
                }
            }
            appendLog(sb.toString());
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void saveReport() {
        final String scan = scanText == null ? "" : scanText.getText().toString();
        final String log;
        synchronized (sessionLog) {
            log = sessionLog.toString();
        }
        worker.submit(() -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Не удалось создать каталог " + dir);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File file = new File(dir, "MK15_PortInspector_" + ts + ".txt");
                try (FileWriter fw = new FileWriter(file)) {
                    fw.write("MK15 Port Inspector 1.0.1\n\n");
                    fw.write(scan);
                    fw.write("\n\n=== Журнал сеанса ===\n");
                    fw.write(log);
                }
                appendLog("Отчёт сохранён: " + file.getAbsolutePath());
                runOnUiThread(() -> Toast.makeText(this,
                        "Отчёт сохранён:\n" + file.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                appendLog("Не удалось сохранить отчёт: " + stackSummary(t));
            }
        });
    }

    private void initRuntimeLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            runtimeLogFile = new File(dir, "MK15_PortInspector_runtime.log");
            try (FileWriter fw = new FileWriter(runtimeLogFile, false)) {
                fw.write("MK15 Port Inspector 1.0.1 runtime log\n");
                fw.write("Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n");
                fw.write("Path: " + runtimeLogFile.getAbsolutePath() + "\n\n");
            }
        } catch (Throwable ignored) {
            runtimeLogFile = null;
        }
    }

    private void clearLog() {
        synchronized (sessionLog) {
            sessionLog.setLength(0);
        }
        if (logText != null) logText.setText("");
        appendLog("Журнал очищен.");
    }

    private void appendLog(String message) {
        if (message == null) return;
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        final String line = stamp + "  " + message + "\n";
        synchronized (sessionLog) {
            sessionLog.append(line);
            if (sessionLog.length() > LOG_LIMIT) {
                sessionLog.delete(0, sessionLog.length() - LOG_LIMIT);
            }
            if (runtimeLogFile != null) {
                try (FileWriter fw = new FileWriter(runtimeLogFile, true)) {
                    fw.write(line);
                } catch (Throwable ignored) {
                }
            }
        }
        runOnUiThread(() -> {
            if (logText != null) {
                String snapshot;
                synchronized (sessionLog) { snapshot = sessionLog.toString(); }
                logText.setText(snapshot);
            }
        });
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private int nextSeq() {
        return sequence.getAndUpdate(v -> (v + 1) & 0xFFFF);
    }

    private void closeSerial() {
        UsbSerialCp210x s = serial;
        serial = null;
        if (s != null) s.close();
        streamEnabled = false;
    }

    private static void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String stackSummary(Throwable t) {
        if (t == null) return "неизвестная ошибка";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    @Override
    protected void onStop() {
        // Если пользователь уходит из приложения, не оставляем RC-поток включённым в фоне.
        if (streamEnabled) stopRcStream(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Синхронно пытаемся отключить 0x42 до закрытия порта: это важнее красивого завершения потока worker.
        UsbSerialCp210x s = serial;
        if (s != null && s.isOpen()) {
            try {
                byte[] off = SiyiProtocol.channelStreamRequest(0, nextSeq());
                for (int i = 0; i < 3; i++) {
                    s.write(off);
                    sleepQuiet(40);
                }
            } catch (Throwable ignored) {}
        }
        streamEnabled = false;
        closeSerial();
        worker.shutdownNow();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
