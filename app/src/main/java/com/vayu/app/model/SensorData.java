package com.vayu.app.model;

import java.util.Date;

/**
 * Holds one parsed sensor reading from the ESP32.
 *
 * ESP32 sends CSV over BLE notify: "temp,humidity,aqi,anionStatus"
 * Example: "24.5,65.2,87,1"
 */
public class SensorData {

    public final float   temperature;   // °C
    public final float   humidity;      // %
    public final int     aqi;           // 0–500
    public final boolean anionOn;       // true if anion generator is running
    public final Date    timestamp;     // time of receipt

    public SensorData(float temperature, float humidity, int aqi, boolean anionOn) {
        this.temperature = temperature;
        this.humidity    = humidity;
        this.aqi         = aqi;
        this.anionOn     = anionOn;
        this.timestamp   = new Date();
    }

    /**
     * Parse a comma-separated string from the ESP32.
     * Expected format: "24.5,65.2,87,1"
     * Returns null if the string is malformed.
     */
    public static SensorData parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.trim().split(",");
        if (parts.length < 4) return null;
        try {
            float   temp    = Float.parseFloat(parts[0].trim());
            float   hum     = Float.parseFloat(parts[1].trim());
            int     aqi     = Integer.parseInt(parts[2].trim());
            boolean anionOn = parts[3].trim().equals("1");
            return new SensorData(temp, hum, aqi, anionOn);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Human-readable AQI category label */
    public String aqiCategory() {
        if (aqi <= 50)  return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for Sensitive";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very Unhealthy";
        return "Hazardous";
    }
}
