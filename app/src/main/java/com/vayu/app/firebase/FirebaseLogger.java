package com.vayu.app.firebase;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.*;

/**
 * Pushes AQI readings to Firebase Realtime Database using the REST API.
 * No google-services.json required — uses HTTP POST directly with OkHttp.
 *
 * Firebase DB URL: https://project-vayu-a6461-default-rtdb.firebaseio.com/
 * Data is written to the /aqi_log node as auto-keyed entries.
 *
 * Pushes every 10 seconds when running (call start() on connect, stop() on disconnect).
 */
public class FirebaseLogger {

    private static final String TAG = "VayuFirebase";

    // Firebase Realtime Database REST endpoint
    // POST to .json appends a new entry with auto-generated key
    private static final String DB_URL        = "https://project-vayu-a6461-default-rtdb.firebaseio.com/aqi_log.json";
    private static final long   PUSH_INTERVAL = 10_000L; // 10 seconds

    // ── Callback interface ────────────────────────────────────────────────────
    public interface Callback {
        void onPushSuccess(int totalPushes);
        void onPushFailed(String reason);
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final OkHttpClient         http      = new OkHttpClient();
    private final Handler              handler   = new Handler(Looper.getMainLooper());
    private final AtomicInteger        pushCount = new AtomicInteger(0);
    private final SimpleDateFormat     sdf       = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final Callback             callback;

    private volatile int     latestAqi = -1;
    private volatile boolean running   = false;
    private Runnable         periodicTask;

    // ── Constructor ───────────────────────────────────────────────────────────
    public FirebaseLogger(Callback callback) {
        this.callback = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call whenever a new AQI reading arrives from BLE. */
    public void updateAqi(int aqi) {
        this.latestAqi = aqi;
    }

    /** Start the periodic push loop (call after BLE connects). */
    public void start() {
        running = true;
        periodicTask = new Runnable() {
            @Override
            public void run() {
                if (running && latestAqi >= 0) {
                    pushToFirebase(latestAqi);
                }
                if (running) handler.postDelayed(this, PUSH_INTERVAL);
            }
        };
        handler.postDelayed(periodicTask, PUSH_INTERVAL);
        Log.d(TAG, "Firebase logger started (push every " + PUSH_INTERVAL / 1000 + " s)");
    }

    /** Stop pushing (call on BLE disconnect). */
    public void stop() {
        running   = false;
        latestAqi = -1;
        if (periodicTask != null) handler.removeCallbacks(periodicTask);
        Log.d(TAG, "Firebase logger stopped");
    }

    /** Call in onDestroy to release OkHttp resources. */
    public void shutdown() {
        stop();
        http.dispatcher().executorService().shutdown();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void pushToFirebase(int aqi) {
        String timestamp = sdf.format(new Date());
        String json      = "{\"aqi\":" + aqi + ",\"timestamp\":\"" + timestamp + "\"}";

        RequestBody body    = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request     request = new Request.Builder().url(DB_URL).post(body).build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Push failed: " + e.getMessage());
                if (callback != null)
                    handler.post(() -> callback.onPushFailed(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
                if (response.isSuccessful()) {
                    int n = pushCount.incrementAndGet();
                    Log.d(TAG, "AQI=" + aqi + " pushed to Firebase (#" + n + ")");
                    if (callback != null)
                        handler.post(() -> callback.onPushSuccess(n));
                } else {
                    String reason = "HTTP " + response.code();
                    Log.w(TAG, "Push rejected: " + reason);
                    if (callback != null)
                        handler.post(() -> callback.onPushFailed(reason));
                }
            }
        });
    }
}
