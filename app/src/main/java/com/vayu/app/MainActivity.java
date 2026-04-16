package com.vayu.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.vayu.app.ble.BLEManager;
import com.vayu.app.firebase.FirebaseLogger;
import com.vayu.app.model.SensorData;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Main screen of Project Vayu.
 *
 * Functionality:
 *  1. BLE scan + connect to ESP32 "Project Vayu" device
 *  2. Receive sensor CSV → parse → display Temperature, Humidity, AQI, Anion status
 *  3. Anion Generator toggle button → send "1" (ON) or "0" (OFF) to ESP32
 *  4. Push AQI to Firebase Realtime DB every 10 seconds via REST API
 */
@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity
        implements BLEManager.Listener, FirebaseLogger.Callback {

    // ── Services ──────────────────────────────────────────────────────────────
    private BLEManager     ble;
    private FirebaseLogger firebase;

    // ── Device scan list ──────────────────────────────────────────────────────
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final List<String>          foundNames   = new ArrayList<>();
    private ArrayAdapter<String>        deviceAdapter;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean anionOn = false;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private TextView  tvStatus, tvDeviceName;
    private TextView  tvTemp, tvHumidity, tvAqi, tvAqiCategory;
    private TextView  tvAnionHw;               // hardware-reported anion state
    private TextView  tvControlMode;           // shows AUTO or MANUAL
    private TextView  tvFirebaseStatus, tvLastUpdate;
    private Button    btnScan, btnDisconnect;
    private Button    btnAnionToggle;

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            if (!grants.containsValue(false)) {
                autoConnectToPairedDevice();
            } else {
                Toast.makeText(this, "Connect permission is required", Toast.LENGTH_LONG).show();
            }
        });

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        ble      = new BLEManager(this, this);
        firebase = new FirebaseLogger(this);

        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, foundNames);

        // ── Button listeners ─────────────────────────────────────────────────
        btnScan.setOnClickListener(v -> {
            if (!ble.isBluetoothEnabled()) {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                return;
            }
            requestPermissionsAndAutoConnect();
        });

        btnDisconnect.setOnClickListener(v -> {
            ble.disconnect();
            firebase.stop();
            setDisconnectedState();
        });

        btnAnionToggle.setOnClickListener(v -> {
            if (!ble.isConnected()) {
                Toast.makeText(this, "Connect to device first", Toast.LENGTH_SHORT).show();
                return;
            }
            anionOn = !anionOn;
            // "1" → ESP32 turns anion ON (manual override)
            // "0" → ESP32 turns anion OFF and resumes auto-AQI control
            ble.sendCommand(anionOn ? "1" : "0");
            updateAnionButton();
            updateControlMode();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ble.disconnect();
        firebase.shutdown();
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private void bindViews() {
        tvStatus          = findViewById(R.id.tv_status);
        tvDeviceName      = findViewById(R.id.tv_device_name);
        tvTemp            = findViewById(R.id.tv_temp);
        tvHumidity        = findViewById(R.id.tv_humidity);
        tvAqi             = findViewById(R.id.tv_aqi);
        tvAqiCategory     = findViewById(R.id.tv_aqi_category);
        tvAnionHw         = findViewById(R.id.tv_anion_hw);
        tvControlMode     = findViewById(R.id.tv_control_mode);
        tvFirebaseStatus  = findViewById(R.id.tv_firebase_status);
        tvLastUpdate      = findViewById(R.id.tv_last_update);
        btnScan           = findViewById(R.id.btn_scan);
        btnDisconnect     = findViewById(R.id.btn_disconnect);
        btnAnionToggle    = findViewById(R.id.btn_anion_toggle);
    }

    // ── Permissions + Auto-Connect ────────────────────────────────────────────
    private void requestPermissionsAndAutoConnect() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (notGranted(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (needed.isEmpty()) {
            autoConnectToPairedDevice();
        } else {
            permLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private boolean notGranted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void autoConnectToPairedDevice() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired != null) {
            for (BluetoothDevice d : paired) {
                String name = d.getName();
                if (name != null && (name.contains("Project Vayu") || name.contains("ESP32"))) {
                    ble.connect(d);
                    return;
                }
            }
        }
        
        Toast.makeText(this, "Project Vayu not found in System Paired devices. Pair it in Settings first!", Toast.LENGTH_LONG).show();
    }

    // ── BLEManager.Listener callbacks ─────────────────────────────────────────
    @Override
    public void onDeviceFound(BluetoothDevice device, String name, int rssi) {
        // Avoid duplicates
        for (BluetoothDevice d : foundDevices)
            if (d.getAddress().equals(device.getAddress())) return;
        foundDevices.add(device);
        foundNames.add(name + "  (" + rssi + " dBm)");
        deviceAdapter.notifyDataSetChanged();
    }

    @Override
    public void onConnected(String deviceName) {
        tvStatus.setText("● Connected");
        tvStatus.setTextColor(Color.parseColor("#22C55E"));   // Green
        tvDeviceName.setText(deviceName);
        btnScan.setVisibility(View.GONE);
        btnDisconnect.setVisibility(View.VISIBLE);
        btnAnionToggle.setEnabled(true);
        firebase.start();
    }

    @Override
    public void onDisconnected() {
        firebase.stop();
        setDisconnectedState();
    }

    @Override
    public void onDataReceived(String csv) {
        SensorData data = SensorData.parse(csv);
        if (data == null) {
            // Bad packet — show raw for debugging
            tvStatus.setText("⚠ Unrecognised: " + csv);
            return;
        }
        firebase.updateAqi(data.aqi);
        updateSensorUI(data);
    }

    @Override
    public void onStatus(String message) {
        tvStatus.setText(message);
    }

    // ── FirebaseLogger.Callback callbacks ─────────────────────────────────────
    @Override
    public void onPushSuccess(int totalPushes) {
        tvFirebaseStatus.setText("☁ Firebase: synced #" + totalPushes);
        tvFirebaseStatus.setTextColor(Color.parseColor("#22C55E")); // Green
    }

    @Override
    public void onPushFailed(String reason) {
        tvFirebaseStatus.setText("☁ Firebase: failed — " + reason);
        tvFirebaseStatus.setTextColor(Color.parseColor("#EF4444")); // Red
    }

    // ── UI update helpers ─────────────────────────────────────────────────────
    private void updateSensorUI(SensorData d) {
        tvTemp.setText(String.format(Locale.getDefault(), "%.1f °C", d.temperature));
        tvHumidity.setText(String.format(Locale.getDefault(), "%.1f %%", d.humidity));
        tvAqi.setText(String.valueOf(d.aqi));
        tvAqiCategory.setText(d.aqiCategory());

        // Colour AQI value by severity
        tvAqi.setTextColor(aqiColor(d.aqi));

        // Hardware-reported anion state (from ESP32 field 4)
        tvAnionHw.setText(d.anionOn ? "ACTIVE" : "IDLE");
        tvAnionHw.setTextColor(d.anionOn
                ? Color.parseColor("#A78BFA")   // Purple when active
                : Color.parseColor("#94A3B8")); // Grey when idle

        // Sync button state with hardware truth
        anionOn = d.anionOn;
        updateAnionButton();
        updateControlMode();

        // Timestamp
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(d.timestamp);
        tvLastUpdate.setText("Last update: " + time);
    }

    private void updateAnionButton() {
        if (anionOn) {
            btnAnionToggle.setText("Turn Anion OFF");
            btnAnionToggle.setBackgroundColor(Color.parseColor("#7C3AED")); // Violet
            btnAnionToggle.setTextColor(Color.WHITE);
        } else {
            btnAnionToggle.setText("Turn Anion ON");
            btnAnionToggle.setBackgroundColor(Color.parseColor("#3B82F6")); // Blue
            btnAnionToggle.setTextColor(Color.WHITE);
        }
    }

    private void updateControlMode() {
        // anionOn=true  → app sent "1" → ESP32 is in manualOverride mode
        // anionOn=false → app sent "0" → ESP32 resumed auto-AQI control
        tvControlMode.setText(anionOn ? "MANUAL" : "AUTO");
        tvControlMode.setTextColor(anionOn
                ? Color.parseColor("#F59E0B")   // Amber for manual
                : Color.parseColor("#22C55E")); // Green for auto
    }

    private void setDisconnectedState() {
        tvStatus.setText("● Disconnected");
        tvStatus.setTextColor(Color.parseColor("#EF4444")); // Red
        tvDeviceName.setText("No device connected");
        btnScan.setVisibility(View.VISIBLE);
        btnDisconnect.setVisibility(View.GONE);
        btnAnionToggle.setEnabled(false);
        tvFirebaseStatus.setText("☁ Firebase: idle");
        tvFirebaseStatus.setTextColor(Color.parseColor("#94A3B8"));
    }

    /** Returns a colour hex string matching the standard AQI colour scale. */
    private int aqiColor(int aqi) {
        if (aqi <= 50)  return Color.parseColor("#22C55E"); // Green
        if (aqi <= 100) return Color.parseColor("#FBBF24"); // Yellow
        if (aqi <= 150) return Color.parseColor("#F97316"); // Orange
        if (aqi <= 200) return Color.parseColor("#EF4444"); // Red
        if (aqi <= 300) return Color.parseColor("#A855F7"); // Purple
        return Color.parseColor("#7F1D1D");                 // Maroon
    }
}
