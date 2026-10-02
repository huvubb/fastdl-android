package com.fastdl.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Sends user feedback as a small multipart request; downloaded files are never included. */
final class FeedbackReporter {
    interface Callback { void done(boolean ok, String message); }
    private static final String ENDPOINT = "https://q23213ddssd.dpdns.org/api/fastdl/feedback";
    private static final long MAX_IMAGE = 20L * 1024 * 1024;
    private static final long MAX_VIDEO = 100L * 1024 * 1024;

    static void send(Context context, String text, Uri image, Uri video, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String boundary = "----FastDL" + UUID.randomUUID().toString().replace("-", "");
                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setConnectTimeout(12000); connection.setReadTimeout(30000);
                connection.setDoOutput(true); connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setRequestProperty("Accept", "application/json");
                try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                    writeText(out, boundary, "message", text == null ? "" : text);
                    writeFile(context.getContentResolver(), out, boundary, "image", image, MAX_IMAGE);
                    writeFile(context.getContentResolver(), out, boundary, "video", video, MAX_VIDEO);
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                callback.done(code >= 200 && code < 300, code >= 200 && code < 300 ? "反馈已提交" : "网站返回错误 " + code);
            } catch (Exception e) { callback.done(false, "提交失败：请检查网络连接"); }
            finally { if (connection != null) connection.disconnect(); }
        }, "fastdl-feedback").start();
    }

    private static void writeText(OutputStream out, String b, String name, String value) throws Exception {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void writeFile(ContentResolver resolver, OutputStream out, String b, String name, Uri uri, long max) throws Exception {
        if (uri == null) return;
        String type = resolver.getType(uri);
        if (type == null || (!(name.equals("image") && type.startsWith("image/")) && !(name.equals("video") && type.startsWith("video/")))) throw new IllegalArgumentException("invalid media");
        long length = 0; try (InputStream in = resolver.openInputStream(uri)) { if (in == null) throw new IllegalArgumentException("unreadable"); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) >= 0) length += n; }
        if (length > max) throw new IllegalArgumentException("too large");
        String filename = name + "-" + System.currentTimeMillis() + (name.equals("image") ? ".jpg" : ".mp4");
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(uri))) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) >= 0) out.write(buf, 0, n); }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
