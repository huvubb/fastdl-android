package com.fastdl.android;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Opt-in, one-shot diagnostics.  It never reads or uploads downloaded files. */
final class DebugReporter {
    private static final String ENDPOINT = "https://q23213ddssd.dpdns.org/api/fastdl/debug";

    private DebugReporter() { }

    static void send(String downloadUrl, long speedBytesPerSecond, String result) {
        new Thread(() -> post(downloadUrl, speedBytesPerSecond, result), "fastdl-debug-report").start();
    }

    private static void post(String downloadUrl, long speedBytesPerSecond, String result) {
        HttpURLConnection connection = null;
        try {
            String body = "{\"download_url\":\"" + json(downloadUrl) + "\","
                    + "\"speed_bps\":" + Math.max(0, speedBytesPerSecond) + ","
                    + "\"result\":\"" + json(result) + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "FastDL-Android/1.6");
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            connection.getResponseCode();
        } catch (Exception ignored) {
            // Telemetry must never delay or alter a download result.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
