package com.vayu.app.model;

import android.graphics.Color;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds cleaned AQI analysis results computed locally from Firebase data.
 */
public class AqiAnalysis {

    public static class Reading {
        public final int aqi;
        public final String timestamp;

        public Reading(int aqi, String timestamp) {
            this.aqi = aqi;
            this.timestamp = timestamp;
        }
    }

    public final List<Reading> readings;
    public final int min;
    public final int max;
    public final double avg;
    public final int readingCount;
    public final String trend;       // "Rising", "Falling", "Stable"
    public final String trendArrow;  // "↑", "↓", "→"
    public final String category;    // "Good", "Moderate", etc.
    public final int categoryColor;
    public String aiInsight = "";

    private AqiAnalysis(List<Reading> readings, int min, int max, double avg,
                        String trend, String trendArrow, String category, int categoryColor) {
        this.readings = readings;
        this.readingCount = readings.size();
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.trend = trend;
        this.trendArrow = trendArrow;
        this.category = category;
        this.categoryColor = categoryColor;
    }

    /**
     * Parse Firebase JSON, clean and compute stats.
     * Firebase data format: { "autokey1": { "aqi": 42, "timestamp": "2026-04-16 10:30:00" }, ... }
     */
    public static AqiAnalysis fromFirebaseJson(String json) {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(json, JsonObject.class);

        List<Reading> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                int aqi = obj.get("aqi").getAsInt();
                String ts = obj.has("timestamp") ? obj.get("timestamp").getAsString() : "";

                // Skip invalid readings
                if (aqi < 0 || aqi > 500) continue;

                // Deduplicate by timestamp
                String key = aqi + "|" + ts;
                if (seen.contains(key)) continue;
                seen.add(key);

                raw.add(new Reading(aqi, ts));
            } catch (Exception ignored) {
                // Skip malformed entries
            }
        }

        if (raw.isEmpty()) return null;

        // Sort by timestamp
        Collections.sort(raw, Comparator.comparing(r -> r.timestamp));

        // Compute stats
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        for (Reading r : raw) {
            if (r.aqi < min) min = r.aqi;
            if (r.aqi > max) max = r.aqi;
            sum += r.aqi;
        }
        double avg = (double) sum / raw.size();

        // Compute trend: compare first half avg to second half avg
        String trend;
        String trendArrow;
        if (raw.size() >= 4) {
            int mid = raw.size() / 2;
            double firstHalf = 0, secondHalf = 0;
            for (int i = 0; i < mid; i++) firstHalf += raw.get(i).aqi;
            for (int i = mid; i < raw.size(); i++) secondHalf += raw.get(i).aqi;
            firstHalf /= mid;
            secondHalf /= (raw.size() - mid);

            double diff = secondHalf - firstHalf;
            if (diff > 5) {
                trend = "Rising";
                trendArrow = "↑";
            } else if (diff < -5) {
                trend = "Falling";
                trendArrow = "↓";
            } else {
                trend = "Stable";
                trendArrow = "→";
            }
        } else {
            trend = "Stable";
            trendArrow = "→";
        }

        // AQI category based on average
        String category;
        int categoryColor;
        if (avg <= 50) {
            category = "Good";
            categoryColor = Color.parseColor("#22C55E");
        } else if (avg <= 100) {
            category = "Moderate";
            categoryColor = Color.parseColor("#EAB308");
        } else if (avg <= 150) {
            category = "Unhealthy (Sensitive)";
            categoryColor = Color.parseColor("#F97316");
        } else if (avg <= 200) {
            category = "Unhealthy";
            categoryColor = Color.parseColor("#EF4444");
        } else if (avg <= 300) {
            category = "Very Unhealthy";
            categoryColor = Color.parseColor("#A855F7");
        } else {
            category = "Hazardous";
            categoryColor = Color.parseColor("#7F1D1D");
        }

        return new AqiAnalysis(raw, min, max, avg, trend, trendArrow, category, categoryColor);
    }

    /**
     * Generate a structured summary for the AI prompt (not raw JSON).
     */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("AQI Analysis Summary:\n");
        sb.append("- Readings analyzed: ").append(readingCount).append("\n");
        sb.append("- Time range: ").append(readings.get(0).timestamp)
          .append(" to ").append(readings.get(readings.size() - 1).timestamp).append("\n");
        sb.append("- Min AQI: ").append(min).append("\n");
        sb.append("- Max AQI: ").append(max).append("\n");
        sb.append("- Average AQI: ").append(String.format("%.1f", avg)).append("\n");
        sb.append("- Category: ").append(category).append("\n");
        sb.append("- Trend: ").append(trend).append("\n");
        sb.append("- Recent values: ");
        int start = Math.max(0, readings.size() - 5);
        for (int i = start; i < readings.size(); i++) {
            sb.append(readings.get(i).aqi);
            if (i < readings.size() - 1) sb.append(", ");
        }
        sb.append("\n");
        return sb.toString();
    }
}
