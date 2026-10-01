package com.fastdl.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/** Alternates fragment connections between active Wi-Fi and cellular networks when available. */
final class NetworkRouter {
    private final ConnectivityManager manager;
    private final boolean enabled;
    private final AtomicInteger turn = new AtomicInteger();
    NetworkRouter(Context context, boolean enabled) {
        this.manager = context.getSystemService(ConnectivityManager.class);
        this.enabled = enabled;
    }
    HttpURLConnection open(URL url) throws IOException {
        if (!enabled || manager == null) return (HttpURLConnection) url.openConnection();
        Network wifi = null, cellular = null;
        for (Network n : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(n);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = n;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = n;
        }
        if (wifi != null && cellular != null) {
            return (HttpURLConnection) ((turn.getAndIncrement() & 1) == 0 ? wifi : cellular).openConnection(url);
        }
        if (wifi != null) return (HttpURLConnection) wifi.openConnection(url);
        if (cellular != null) return (HttpURLConnection) cellular.openConnection(url);
        return (HttpURLConnection) url.openConnection();
    }
    String state() { return enabled ? "双网加速已开启" : "单网模式"; }
}
