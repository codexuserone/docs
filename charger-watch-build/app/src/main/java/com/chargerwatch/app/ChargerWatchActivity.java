package com.chargerwatch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public final class ChargerWatchActivity extends Activity {
    private static final long POLL_MS = 75L;
    private static final int COLOR_BG = Color.rgb(245, 245, 247);
    private static final int COLOR_TEXT = Color.rgb(25, 25, 28);
    private static final int COLOR_MUTED = Color.rgb(92, 92, 100);
    private static final int COLOR_OK = Color.rgb(0, 122, 68);
    private static final int COLOR_BAD = Color.rgb(176, 22, 22);
    private static final int COLOR_CARD = Color.WHITE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder(8192);
    private final SimpleDateFormat timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView statusView;
    private TextView detailView;
    private TextView countersView;
    private TextView logView;
    private ScrollView logScroll;

    private BatteryManager batteryManager;
    private UsbManager usbManager;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    private long sessionStarted;
    private long polls;
    private long detections;
    private long broadcastEvents;
    private boolean receiverRegistered;
    private boolean lastExternalPower;
    private int lastPlugged = Integer.MIN_VALUE;
    private int lastStatus = Integer.MIN_VALUE;
    private int lastUsbCount = Integer.MIN_VALUE;
    private long lastSignalElapsed = -1L;
    private String lastSignal = "none";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            broadcastEvents++;

            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                recordDetection("ACTION_POWER_CONNECTED broadcast");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                appendLog("POWER DISCONNECTED broadcast");
            } else if (BatteryManager.ACTION_CHARGING.equals(action)) {
                recordDetection("ACTION_CHARGING broadcast");
            } else if (BatteryManager.ACTION_DISCHARGING.equals(action)) {
                appendLog("ACTION_DISCHARGING broadcast");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                recordDetection("USB DEVICE ATTACHED" + describeUsb(device));
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                appendLog("USB DEVICE DETACHED" + describeUsb(device));
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                recordDetection("USB ACCESSORY ATTACHED");
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                appendLog("USB ACCESSORY DETACHED");
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                boolean external = plugged != 0;
                if (external && !lastExternalPower) {
                    recordDetection("BATTERY_CHANGED reports plugged=" + pluggedName(plugged));
                }
                if (plugged != lastPlugged || status != lastStatus) {
                    appendLog("BATTERY state: plugged=" + pluggedName(plugged) + ", status=" + statusName(status));
                }
                lastExternalPower = external;
                lastPlugged = plugged;
                lastStatus = status;
            } else {
                Bundle extras = intent.getExtras();
                appendLog("USB system broadcast: " + action + (extras == null ? "" : " " + compactExtras(extras)));
                if (extras != null && (extras.getBoolean("connected", false) || extras.getBoolean("configured", false))) {
                    recordDetection("USB system state reports connected");
                }
            }
            pollNow();
        }
    };

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            pollNow();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
        buildUi();
        resetSession();
        showLimitsOnce();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerMonitors();
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(poller);
        unregisterMonitors();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (toneGenerator != null) toneGenerator.release();
        super.onDestroy();
    }

    private void registerMonitors() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(BatteryManager.ACTION_CHARGING);
        filter.addAction(BatteryManager.ACTION_DISCHARGING);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction("android.hardware.usb.action.USB_STATE");
        filter.addAction("com.samsung.android.intent.action.USB_STATE");
        filter.addAction("com.samsung.intent.action.USB_STATE");

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterMonitors() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(COLOR_BG);

        TextView title = text("Charger Watch", 25, Typeface.BOLD, COLOR_TEXT);
        root.addView(title, matchWrap());

        TextView instruction = text(
                "First plug in a known-good charger to prove the detector works. Then unplug it and test the dead charger. Leave this screen open.",
                14, Typeface.NORMAL, COLOR_MUTED);
        instruction.setPadding(0, dp(4), 0, dp(12));
        root.addView(instruction, matchWrap());

        statusView = text("NO EXTERNAL POWER", 25, Typeface.BOLD, COLOR_BAD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(12), dp(18), dp(12), dp(18));
        statusView.setBackgroundColor(COLOR_CARD);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, dp(76)));

        countersView = text("", 13, Typeface.BOLD, COLOR_TEXT);
        countersView.setPadding(0, dp(10), 0, dp(7));
        root.addView(countersView, matchWrap());

        detailView = text("", 14, Typeface.NORMAL, COLOR_TEXT);
        detailView.setTypeface(Typeface.MONOSPACE);
        detailView.setTextIsSelectable(true);
        detailView.setPadding(dp(12), dp(11), dp(12), dp(11));
        detailView.setBackgroundColor(COLOR_CARD);
        root.addView(detailView, new LinearLayout.LayoutParams(-1, dp(214)));

        HorizontalScrollView buttonScroll = new HorizontalScrollView(this);
        buttonScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, dp(8));

        Button reset = button("Reset session");
        reset.setOnClickListener(v -> resetSession());
        buttons.addView(reset);

        Button testAlert = button("Test alert");
        testAlert.setOnClickListener(v -> {
            appendLog("Manual alert test");
            alertUser();
        });
        buttons.addView(testAlert);

        Button copy = button("Copy log");
        copy.setOnClickListener(v -> copyLog());
        buttons.addView(copy);

        Button share = button("Share log");
        share.setOnClickListener(v -> shareLog());
        buttons.addView(share);

        buttonScroll.addView(buttons);
        root.addView(buttonScroll, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView logLabel = text("EVENT LOG", 12, Typeface.BOLD, COLOR_MUTED);
        root.addView(logLabel, matchWrap());

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(Color.rgb(24, 24, 27));
        logView = text("", 12, Typeface.NORMAL, Color.rgb(235, 235, 240));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private void resetSession() {
        sessionStarted = SystemClock.elapsedRealtime();
        polls = 0;
        detections = 0;
        broadcastEvents = 0;
        lastSignalElapsed = -1L;
        lastSignal = "none";
        lastExternalPower = false;
        lastPlugged = Integer.MIN_VALUE;
        lastStatus = Integer.MIN_VALUE;
        lastUsbCount = Integer.MIN_VALUE;
        log.setLength(0);
        appendLog("Session started");
        appendLog("Polling every " + POLL_MS + " ms; broadcasts active while screen is open");
        pollNow();
    }

    private void pollNow() {
        polls++;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = battery == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        int voltageMv = battery == null ? Integer.MIN_VALUE
                : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
        int temperatureTenths = battery == null ? Integer.MIN_VALUE
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);

        boolean chargingApi = Build.VERSION.SDK_INT >= 23 && batteryManager != null && batteryManager.isCharging();
        boolean external = plugged != 0;
        int currentNow = propertyInt(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int currentAverage = propertyInt(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        int capacity = propertyInt(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int chargeCounter = propertyInt(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long energyCounter = propertyLong(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

        HashMap<String, UsbDevice> devices = usbManager == null ? new HashMap<>() : usbManager.getDeviceList();
        int usbCount = devices == null ? 0 : devices.size();

        if (external && !lastExternalPower) {
            recordDetection("POLL reports external power: " + pluggedName(plugged));
        } else if (!external && lastExternalPower) {
            appendLog("POLL reports external power removed");
        }
        if (usbCount != lastUsbCount && lastUsbCount != Integer.MIN_VALUE) {
            appendLog("USB device inventory changed: " + lastUsbCount + " -> " + usbCount);
            if (usbCount > lastUsbCount) recordDetection("USB device inventory increased");
        }

        lastExternalPower = external;
        lastPlugged = plugged;
        lastStatus = status;
        lastUsbCount = usbCount;

        statusView.setText(external ? "POWER DETECTED" : "NO EXTERNAL POWER");
        statusView.setTextColor(external ? COLOR_OK : COLOR_BAD);

        long elapsed = SystemClock.elapsedRealtime() - sessionStarted;
        String lastAgo = lastSignalElapsed < 0 ? "never" : formatDuration(SystemClock.elapsedRealtime() - lastSignalElapsed) + " ago";
        countersView.setText("Runtime " + formatDuration(elapsed)
                + "   •   Polls " + polls
                + "   •   Detections " + detections
                + "\nLast signal: " + lastSignal + " (" + lastAgo + ")");

        StringBuilder d = new StringBuilder(512);
        d.append("External input : ").append(external ? "YES" : "NO").append('\n');
        d.append("Plug type      : ").append(pluggedName(plugged)).append('\n');
        d.append("Battery status : ").append(statusName(status)).append('\n');
        d.append("isCharging API : ").append(chargingApi).append('\n');
        d.append("Battery level  : ").append(percent < 0 ? "unavailable" : percent + "%").append('\n');
        d.append("Voltage        : ").append(formatMv(voltageMv)).append('\n');
        d.append("Current now    : ").append(formatUa(currentNow)).append(" (raw sign varies by phone)").append('\n');
        d.append("Current average: ").append(formatUa(currentAverage)).append('\n');
        d.append("Temperature    : ").append(formatTemp(temperatureTenths)).append('\n');
        d.append("Capacity API   : ").append(formatProperty(capacity, "%")).append('\n');
        d.append("Charge counter : ").append(formatProperty(chargeCounter, " µAh")).append('\n');
        d.append("Energy counter : ").append(formatLongProperty(energyCounter, " nWh")).append('\n');
        d.append("USB devices    : ").append(usbCount).append('\n');
        d.append("Broadcasts     : ").append(broadcastEvents);
        detailView.setText(d.toString());
    }

    private void recordDetection(String source) {
        detections++;
        lastSignalElapsed = SystemClock.elapsedRealtime();
        lastSignal = source;
        appendLog("*** DETECTED: " + source + " ***");
        alertUser();
    }

    private void alertUser() {
        try {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
        } catch (RuntimeException ignored) {
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 70, 220}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 120, 70, 220}, -1);
            }
        }
    }

    private void appendLog(String message) {
        String line = timestamp.format(new Date()) + "  " + message + "\n";
        log.append(line);
        if (log.length() > 60000) log.delete(0, 12000);
        if (logView != null) {
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Charger Watch log", makeReport()));
            appendLog("Report copied to clipboard");
        }
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Charger Watch report");
        send.putExtra(Intent.EXTRA_TEXT, makeReport());
        startActivity(Intent.createChooser(send, "Share test report"));
    }

    private String makeReport() {
        return "Charger Watch 1.0\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Poll interval: " + POLL_MS + " ms\n"
                + "Detections: " + detections + "\n\n"
                + detailView.getText() + "\n\nEVENT LOG\n" + log;
    }

    private void showLimitsOnce() {
        new AlertDialog.Builder(this)
                .setTitle("What this test can prove")
                .setMessage("This app records every charger or USB event that Android exposes, plus rapid battery-state polling. It can prove whether the phone ever detects the charger. It cannot measure the USB-C CC pins or identify the failed component inside the charger without external test hardware.\n\nUse a known-good charger first. If that is detected and the suspect charger never is, Android is receiving no usable connection from the suspect charger.")
                .setPositiveButton("Start test", null)
                .show();
    }

    private int propertyInt(int id) {
        if (batteryManager == null) return Integer.MIN_VALUE;
        try {
            return batteryManager.getIntProperty(id);
        } catch (RuntimeException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private long propertyLong(int id) {
        if (batteryManager == null) return Long.MIN_VALUE;
        try {
            return batteryManager.getLongProperty(id);
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String describeUsb(UsbDevice device) {
        if (device == null) return "";
        return " [vid=" + device.getVendorId() + ", pid=" + device.getProductId()
                + ", class=" + device.getDeviceClass() + "]";
    }

    private static String compactExtras(Bundle extras) {
        StringBuilder out = new StringBuilder("{");
        int shown = 0;
        for (String key : extras.keySet()) {
            if (shown++ >= 8) {
                out.append("…");
                break;
            }
            if (shown > 1) out.append(", ");
            Object value = extras.get(key);
            out.append(key).append('=').append(value);
        }
        return out.append('}').toString();
    }

    private static String pluggedName(int plugged) {
        if (plugged == 0) return "NONE";
        StringBuilder out = new StringBuilder();
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) out.append("AC+");
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) out.append("USB+");
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) out.append("WIRELESS+");
        if (Build.VERSION.SDK_INT >= 33 && (plugged & BatteryManager.BATTERY_PLUGGED_DOCK) != 0) out.append("DOCK+");
        if (out.length() == 0) return "UNKNOWN(" + plugged + ")";
        out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String statusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "CHARGING";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "DISCHARGING";
            case BatteryManager.BATTERY_STATUS_FULL: return "FULL";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "NOT_CHARGING";
            default: return "UNKNOWN(" + status + ")";
        }
    }

    private static String formatMv(int mv) {
        return mv == Integer.MIN_VALUE ? "unavailable" : String.format(Locale.US, "%.3f V (%d mV)", mv / 1000.0, mv);
    }

    private static String formatUa(int ua) {
        return ua == Integer.MIN_VALUE ? "unavailable" : String.format(Locale.US, "%+.3f A (%d µA)", ua / 1_000_000.0, ua);
    }

    private static String formatTemp(int tenths) {
        return tenths == Integer.MIN_VALUE ? "unavailable" : String.format(Locale.US, "%.1f °C", tenths / 10.0);
    }

    private static String formatProperty(int value, String suffix) {
        return value == Integer.MIN_VALUE ? "unavailable" : value + suffix;
    }

    private static String formatLongProperty(long value, String suffix) {
        return value == Long.MIN_VALUE ? "unavailable" : value + suffix;
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(46));
        params.setMarginEnd(dp(7));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
