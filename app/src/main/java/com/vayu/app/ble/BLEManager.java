package com.vayu.app.ble;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Manages the full BLE lifecycle for Project Vayu:
 *   - Scanning (filtered by Service UUID)
 *   - GATT connection + service discovery
 *   - Subscribing to notifications (sensor data from ESP32)
 *   - Writing commands to the ESP32 (anion on/off)
 *
 * Thread safety: all listener callbacks are dispatched on the main thread.
 */
@SuppressLint("MissingPermission")
public class BLEManager {

    private static final String TAG = "VayuBLE";

    // ── Project Vayu ESP32 UUIDs ─────────────────────────────────────────────
    // Must match firmware exactly (firmware: ProjectVayu.ino)
    public static final UUID SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    public static final UUID NOTIFY_UUID  = UUID.fromString("00002A57-0000-1000-8000-00805F9B34FB"); // ESP32 → App (notify)
    public static final UUID WRITE_UUID   = UUID.fromString("00002A58-0000-1000-8000-00805F9B34FB"); // App → ESP32 (write)
    private static final UUID CCCD_UUID   = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // ── Listener interface ────────────────────────────────────────────────────
    public interface Listener {
        void onDeviceFound(BluetoothDevice device, String name, int rssi);
        void onConnected(String deviceName);
        void onDisconnected();
        void onDataReceived(String rawCsv);   // called once per ESP32 notify packet
        void onStatus(String message);
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final Context  context;
    private final Listener listener;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter              adapter;
    private BluetoothLeScanner            scanner;
    private BluetoothGatt                 gatt;
    private BluetoothGattCharacteristic   writeChar;
    private volatile boolean              scanning = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public BLEManager(Context context, Listener listener) {
        this.context  = context;
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isConnected() {
        return gatt != null; // We no longer strictly require writeChar to be connected
    }

    /** Start scanning for devices that advertise the Project Vayu service UUID. */
    public void startScan() {
        if (!isBluetoothEnabled()) {
            listener.onStatus("Bluetooth is OFF — please enable it");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        // Removed ScanFilter: ESP32 128-bit UUIDs often don't fit in the 31-byte advertising 
        // payload, causing Android to completely hide the device if a strict filter is used.
        scanner.startScan(null, settings, scanCallback);
        scanning = true;
        listener.onStatus("Scanning for Project Vayu…");
        // Auto-stop after 15 s
        mainHandler.postDelayed(this::stopScan, 15_000);
    }

    public void stopScan() {
        if (scanner != null && scanning) {
            scanner.stopScan(scanCallback);
            scanning = false;
        }
    }

    /** Connect to a device found during scan. */
    public void connect(BluetoothDevice device) {
        stopScan();
        listener.onStatus("Connecting to " + device.getName() + "…");
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    /** Cleanly close the GATT connection. */
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        writeChar = null;
    }

    /**
     * Send a command string to the ESP32 via the write characteristic.
     * ESP32 firmware expects:
     *   "1" → turn anion generator ON (manual override)
     *   "0" → turn anion generator OFF (resume auto-AQI control)
     */
    public void sendCommand(String cmd) {
        if (gatt == null || writeChar == null) {
            listener.onStatus("Cannot send — not connected");
            return;
        }
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(writeChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeChar.setValue(bytes);
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(writeChar);
        }
        Log.d(TAG, "Sent command: " + cmd);
    }

    // ── BLE Scan Callback ─────────────────────────────────────────────────────
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null || name.isEmpty()) name = device.getAddress();
            listener.onDeviceFound(device, name, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            listener.onStatus("Scan failed (error " + errorCode + ")");
        }
    };

    // ── GATT Callback ─────────────────────────────────────────────────────────
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post(() -> listener.onStatus("Connected — discovering services…"));
                g.requestMtu(512);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt      = null;
                writeChar = null;
                mainHandler.post(listener::onDisconnected);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                mainHandler.post(() -> listener.onStatus("Project Vayu service not found — check firmware"));
                return;
            }

            BluetoothGattCharacteristic notifyChar = service.getCharacteristic(NOTIFY_UUID);
            writeChar = service.getCharacteristic(WRITE_UUID);

            if (notifyChar == null) {
                mainHandler.post(() -> listener.onStatus("Notify characteristic not found!"));
                return;
            }

            // Enable notifications so ESP32 data reaches onCharacteristicChanged
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor desc = notifyChar.getDescriptor(CCCD_UUID);
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            }

            String devName = g.getDevice().getName();
            mainHandler.post(() -> listener.onConnected(devName != null ? devName : "Project Vayu"));
        }

        // Android < 13
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            deliverData(c.getValue());
        }

        // Android 13+
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliverData(value);
        }
    };

    /**
     * Each BLE notify from the ESP32 is one complete CSV packet.
     * e.g. "24.5,65.2,87,1"
     * Deliver it directly to the listener on the main thread.
     */
    private void deliverData(byte[] data) {
        if (data == null || data.length == 0) return;
        String csv = new String(data, StandardCharsets.UTF_8).trim();
        if (!csv.isEmpty()) {
            Log.d(TAG, "BLE RX: " + csv);
            mainHandler.post(() -> listener.onDataReceived(csv));
        }
    }
}
