package com.fastdl.android;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small, dependency-free GitHub Releases updater. */
final class UpdateChecker {
    private static final String LATEST = "https://api.github.com/repos/huvubb/fastdl-android/releases/latest";

    interface Progress { void onProgress(long done, long total); }
    static final class Result {
        final String version, apkUrl;
        final boolean isNewer;
        final String currentVersion;
        Result(String version, String apkUrl, boolean isNewer, String currentVersion) { this.version = version; this.apkUrl = apkUrl; this.isNewer = isNewer; this.currentVersion = currentVersion; }
    }

    static Result latest(Activity activity) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000); c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) throw new Exception("GitHub 返回 " + c.getResponseCode());
        String json = read(c.getInputStream());
        JSONObject release = new JSONObject(json);
        String version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
        JSONArray assets = release.optJSONArray("assets");
        String url = "";
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "").toLowerCase();
            if (name.endsWith(".apk")) { url = asset.optString("browser_download_url", ""); break; }
        }
        if (version.isEmpty() || url.isEmpty()) throw new Exception("最新发布中没有 APK");
        String local = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        return new Result(version, url, compare(version, local) > 0, local);
    }

    static Uri download(Activity activity, String source, String version, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        if (c.getResponseCode() / 100 != 2) throw new Exception("更新包下载失败：" + c.getResponseCode());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "FastDL-" + version + ".apk");
        values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastDL");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("无法创建更新文件");
        try (InputStream in = c.getInputStream(); OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入更新文件");
            byte[] buffer = new byte[128 * 1024]; long done = 0, total = c.getContentLengthLong();
            for (int n; (n = in.read(buffer)) >= 0;) { out.write(buffer, 0, n); done += n; progress.onProgress(done, total); }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) { activity.getContentResolver().delete(uri, null, null); throw error; }
    }

    static void install(Activity activity, Uri apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(apk,
                "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private static String read(InputStream stream) throws Exception {
        byte[] data = new byte[8192]; StringBuilder out = new StringBuilder();
        for (int n; (n = stream.read(data)) >= 0;) out.append(new String(data, 0, n, "UTF-8"));
        return out.toString();
    }
    private static int compare(String a, String b) {
        String[] left = a.split("\\."); String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? number(left[i]) : 0, y = i < right.length ? number(right[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        } return 0;
    }
    private static int number(String value) { try { return Integer.parseInt(value.replaceAll("\\D.*", "")); } catch (Exception ignored) { return 0; } }
}
