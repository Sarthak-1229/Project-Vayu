package com.vayu.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.vayu.app.ai.GeminiService;
import com.vayu.app.ble.BLEManager;
import com.vayu.app.firebase.FirebaseLogger;
import com.vayu.app.model.SensorData;
import com.vayu.app.ui.AnalyzerFragment;
import com.vayu.app.ui.ChatFragment;
import com.vayu.app.ui.DashboardFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements BLEManager.Listener, FirebaseLogger.Callback {

    private BLEManager ble;
    private FirebaseLogger firebase;
    private GeminiService gemini;

    private SensorData lastSensorData = null;
    private String connectedDeviceName = null;

    private final DashboardFragment dashboardFragment = new DashboardFragment();
    private final ChatFragment chatFragment = new ChatFragment();
    private final AnalyzerFragment analyzerFragment = new AnalyzerFragment();
    private Fragment activeFragment = dashboardFragment;

    private final ActivityResultLauncher<String[]> permLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            if (!grants.containsValue(false)) {
                autoConnectToPairedDevice();
            } else {
                Toast.makeText(this, "Connect permission is required", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ble = new BLEManager(this, this);
        firebase = new FirebaseLogger(this);
        // Securely retrieve the key from BuildConfig (injected from local.properties)
        gemini = new GeminiService(BuildConfig.GROQ_API_KEY);

        BottomNavigationView nav = findViewById(R.id.bottom_navigation);
        
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, analyzerFragment, "3").hide(analyzerFragment)
                .add(R.id.fragment_container, chatFragment, "2").hide(chatFragment)
                .add(R.id.fragment_container, dashboardFragment, "1").commit();

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment target = null;
            if (id == R.id.nav_dashboard) target = dashboardFragment;
            else if (id == R.id.nav_chat) target = chatFragment;
            else if (id == R.id.nav_analyzer) target = analyzerFragment;

            if (target != null && target != activeFragment) {
                getSupportFragmentManager().beginTransaction().hide(activeFragment).show(target).commit();
                activeFragment = target;
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ble.disconnect();
        firebase.shutdown();
    }

    // ── Getters for Fragments ────────────────────────────────────────────────
    public FirebaseLogger getFirebaseLogger() { return firebase; }
    public GeminiService getGeminiService() { return gemini; }
    public boolean isBleConnected() { return ble.isConnected(); }
    public String getConnectedDeviceName() { return connectedDeviceName; }
    public SensorData getLastSensorData() { return lastSensorData; }

    // ── Actions from Dashboard ───────────────────────────────────────────────
    public void startConnection() {
        if (!ble.isBluetoothEnabled()) {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (needed.isEmpty()) {
            autoConnectToPairedDevice();
        } else {
            permLauncher.launch(needed.toArray(new String[0]));
        }
    }

    public void disconnect() {
        ble.disconnect();
        firebase.stop();
        connectedDeviceName = null;
        dashboardFragment.setDisconnectedState();
    }

    public void sendAnionCommand(String cmd) {
        ble.sendCommand(cmd);
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
        Toast.makeText(this, "Project Vayu not paired! Pair in OS Settings first.", Toast.LENGTH_LONG).show();
    }

    // ── BLEManager.Listener ──────────────────────────────────────────────────
    @Override
    public void onDeviceFound(BluetoothDevice device, String name, int rssi) {}

    @Override
    public void onConnected(String deviceName) {
        connectedDeviceName = deviceName;
        firebase.start();
        runOnUiThread(() -> dashboardFragment.setConnectedState(deviceName));
    }

    @Override
    public void onDisconnected() {
        connectedDeviceName = null;
        firebase.stop();
        runOnUiThread(() -> dashboardFragment.setDisconnectedState());
    }

    @Override
    public void onDataReceived(String csv) {
        SensorData data = SensorData.parse(csv);
        if (data == null) return;
        lastSensorData = data;
        firebase.updateAqi(data.aqi);
        runOnUiThread(() -> dashboardFragment.updateSensorUI(data));
    }

    @Override
    public void onStatus(String message) {}

    // ── FirebaseLogger.Callback ──────────────────────────────────────────────
    @Override
    public void onPushSuccess(int totalPushes) {
        runOnUiThread(() -> dashboardFragment.setFirebaseStatus("☁ Firebase: synced #" + totalPushes, true));
    }

    @Override
    public void onPushFailed(String reason) {
        runOnUiThread(() -> dashboardFragment.setFirebaseStatus("☁ Firebase: failed - " + reason, false));
    }
}
