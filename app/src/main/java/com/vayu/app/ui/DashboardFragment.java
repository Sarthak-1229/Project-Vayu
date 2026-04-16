package com.vayu.app.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vayu.app.MainActivity;
import com.vayu.app.R;
import com.vayu.app.model.SensorData;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private TextView tvStatus, tvDeviceName;
    private TextView tvTemp, tvHumidity, tvAqi, tvAqiCategory;
    private TextView tvAnionHw, tvControlMode;
    private TextView tvFirebaseStatus, tvLastUpdate;
    private Button btnScan, btnDisconnect, btnAnionToggle;

    private boolean anionOn = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_dashboard, container, false);
        bindViews(v);

        btnScan.setOnClickListener(view -> {
            ((MainActivity) getActivity()).startConnection();
        });

        btnDisconnect.setOnClickListener(view -> {
            ((MainActivity) getActivity()).disconnect();
        });

        btnAnionToggle.setOnClickListener(view -> {
            if (!((MainActivity) getActivity()).isBleConnected()) {
                Toast.makeText(getContext(), "Connect to device first", Toast.LENGTH_SHORT).show();
                return;
            }
            anionOn = !anionOn;
            ((MainActivity) getActivity()).sendAnionCommand(anionOn ? "1" : "0");
            updateAnionButton();
            updateControlMode();
        });

        // Initialize display state from MainActivity
        syncStateWithActivity();

        return v;
    }

    private void bindViews(View v) {
        tvStatus          = v.findViewById(R.id.tv_status);
        tvDeviceName      = v.findViewById(R.id.tv_device_name);
        tvTemp            = v.findViewById(R.id.tv_temp);
        tvHumidity        = v.findViewById(R.id.tv_humidity);
        tvAqi             = v.findViewById(R.id.tv_aqi);
        tvAqiCategory     = v.findViewById(R.id.tv_aqi_category);
        tvAnionHw         = v.findViewById(R.id.tv_anion_hw);
        tvControlMode     = v.findViewById(R.id.tv_control_mode);
        tvFirebaseStatus  = v.findViewById(R.id.tv_firebase_status);
        tvLastUpdate      = v.findViewById(R.id.tv_last_update);
        btnScan           = v.findViewById(R.id.btn_scan);
        btnDisconnect     = v.findViewById(R.id.btn_disconnect);
        btnAnionToggle    = v.findViewById(R.id.btn_anion_toggle);
    }

    public void syncStateWithActivity() {
        if (getActivity() == null) return;
        MainActivity activity = (MainActivity) getActivity();

        if (activity.isBleConnected()) {
            setConnectedState(activity.getConnectedDeviceName());
        } else {
            setDisconnectedState();
        }

        if (activity.getLastSensorData() != null) {
            updateSensorUI(activity.getLastSensorData());
        }
    }

    public void setConnectedState(String deviceName) {
        if (tvStatus == null) return;
        tvStatus.setText("● Connected");
        tvStatus.setTextColor(Color.parseColor("#22C55E"));
        tvDeviceName.setText(deviceName);
        btnScan.setVisibility(View.GONE);
        btnDisconnect.setVisibility(View.VISIBLE);
        btnAnionToggle.setEnabled(true);
    }

    public void setDisconnectedState() {
        if (tvStatus == null) return;
        tvStatus.setText("● Disconnected");
        tvStatus.setTextColor(Color.parseColor("#EF4444"));
        tvDeviceName.setText("No device connected");
        btnScan.setVisibility(View.VISIBLE);
        btnDisconnect.setVisibility(View.GONE);
        btnAnionToggle.setEnabled(false);
        tvFirebaseStatus.setText("☁ Firebase: idle");
        tvFirebaseStatus.setTextColor(Color.parseColor("#94A3B8"));
        
        tvTemp.setText("--.- °C");
        tvHumidity.setText("--.- %");
        tvAqi.setText("---");
        tvAnionHw.setText("---");
    }

    public void updateSensorUI(SensorData d) {
        if (tvTemp == null) return;
        tvTemp.setText(String.format(Locale.getDefault(), "%.1f °C", d.temperature));
        tvHumidity.setText(String.format(Locale.getDefault(), "%.1f %%", d.humidity));
        tvAqi.setText(String.valueOf(d.aqi));
        tvAqiCategory.setText(d.aqiCategory());
        tvAqi.setTextColor(aqiColor(d.aqi));

        tvAnionHw.setText(d.anionOn ? "ACTIVE" : "IDLE");
        tvAnionHw.setTextColor(d.anionOn ? Color.parseColor("#A78BFA") : Color.parseColor("#94A3B8"));

        anionOn = d.anionOn;
        updateAnionButton();
        updateControlMode();

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(d.timestamp);
        tvLastUpdate.setText("Last update: " + time);
    }

    public void setFirebaseStatus(String summary, boolean isSuccess) {
        if (tvFirebaseStatus == null) return;
        tvFirebaseStatus.setText(summary);
        tvFirebaseStatus.setTextColor(isSuccess ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
    }

    private void updateAnionButton() {
        if (anionOn) {
            btnAnionToggle.setText("Turn Anion OFF");
            btnAnionToggle.setBackgroundColor(Color.parseColor("#7C3AED"));
            btnAnionToggle.setTextColor(Color.WHITE);
        } else {
            btnAnionToggle.setText("Turn Anion ON");
            btnAnionToggle.setBackgroundColor(Color.parseColor("#3B82F6"));
            btnAnionToggle.setTextColor(Color.WHITE);
        }
    }

    private void updateControlMode() {
        tvControlMode.setText(anionOn ? "MANUAL" : "AUTO");
        tvControlMode.setTextColor(anionOn ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E"));
    }

    private int aqiColor(int aqi) {
        if (aqi <= 50) return Color.parseColor("#22C55E");
        if (aqi <= 100) return Color.parseColor("#FBBF24");
        if (aqi <= 150) return Color.parseColor("#F97316");
        if (aqi <= 200) return Color.parseColor("#EF4444");
        if (aqi <= 300) return Color.parseColor("#A855F7");
        return Color.parseColor("#7F1D1D");
    }
}
