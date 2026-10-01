package com.fastdl.android;

import android.content.Intent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Transfer-only settings. Kept small so they can safely cross a service Intent. */
final class DownloadOptions {
    static final int AUTO_THREADS = 128;
    static final String EXTRA_THREADS = "threads";
    static final String EXTRA_LIMIT = "limit";
    static final String EXTRA_PROXY = "proxy";
    static final String EXTRA_HEADERS = "headers";
    static final String EXTRA_DUAL_NETWORK = "dual_network";

    final int threads;
    final long limitBytesPerSecond;
    final String proxy;
    final Map<String, String> headers;
    final boolean dualNetwork;

    DownloadOptions(int threads, long limitBytesPerSecond, String proxy, Map<String, String> headers, boolean dualNetwork) {
        this.threads = Math.max(1, Math.min(128, threads));
        this.limitBytesPerSecond = Math.max(0, limitBytesPerSecond);
        this.proxy = proxy == null ? "" : proxy.trim();
        this.headers = headers;
        this.dualNetwork = dualNetwork;
    }

    static DownloadOptions from(Intent intent) {
        return new DownloadOptions(intent.getIntExtra(EXTRA_THREADS, AUTO_THREADS),
                parseLimit(intent.getStringExtra(EXTRA_LIMIT)),
                intent.getStringExtra(EXTRA_PROXY), parseHeaders(intent.getStringExtra(EXTRA_HEADERS)), intent.getBooleanExtra(EXTRA_DUAL_NETWORK, true));
    }

    static long parseLimit(String input) {
        if (input == null || input.trim().isEmpty()) return 0;
        String text = input.trim().toUpperCase(Locale.US).replace("/S", "");
        long scale = 1;
        if (text.endsWith("K")) { scale = 1024L; text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("M")) { scale = 1024L * 1024L; text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("G")) { scale = 1024L * 1024L * 1024L; text = text.substring(0, text.length() - 1); }
        try { return Math.round(Double.parseDouble(text.trim()) * scale); }
        catch (NumberFormatException ignored) { return 0; }
    }

    static Map<String, String> parseHeaders(String input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (String line : input.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) result.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return result;
    }
}
