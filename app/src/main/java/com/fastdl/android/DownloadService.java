package com.fastdl.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.ContentValues;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.IBinder;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground owner for a download. Android may otherwise stop long network work. */
public final class DownloadService extends Service {
    public static final String ACTION_START = "com.fastdl.android.START";
    public static final String ACTION_PAUSE = "com.fastdl.android.PAUSE";
    public static final String ACTION_CANCEL = "com.fastdl.android.CANCEL";
    public static final String ACTION_UPDATE = "com.fastdl.android.UPDATE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_TREE_URI = "tree_uri";
    private static final int NOTIFICATION_ID = 100;
    private static final String CHANNEL = "fastdl_downloads";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile HttpDownloader active;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            if (active != null) active.pause();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            if (active != null) active.cancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || active != null) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("正在准备下载", 0, 0));
        File base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        DownloadOptions options = DownloadOptions.from(intent);
        active = new HttpDownloader(base, url, options, new NetworkRouter(this, options.dualNetwork), this::report);
        String treeUri = intent.getStringExtra(EXTRA_TREE_URI);
        long startedAt = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                active.run();
                File completed = newestCompleted(base, startedAt);
                if (completed != null) {
                    if (treeUri == null || treeUri.isEmpty() || !publishToTree(completed, Uri.parse(treeUri))) {
                        publishToDownloads(completed);
                    }
                }
            } finally {
                active = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private File newestCompleted(File base, long startedAt) {
        File[] files = base == null ? null : base.listFiles();
        File newest = null;
        if (files != null) for (File file : files) {
            String n = file.getName();
            if (!file.isFile() || n.startsWith(".") || n.endsWith(".partial") || n.endsWith(".tmp")
                    || n.contains(".fastdl")) continue;
            if (file.lastModified() >= startedAt && (newest == null || file.lastModified() > newest.lastModified())) newest = file;
        }
        return newest;
    }

    private boolean publishToDownloads(File source) {
        if (Build.VERSION.SDK_INT < 29) return false;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, mime(source.getName()));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastDL");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        try (FileInputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("无法打开公共下载目录");
            byte[] buffer = new byte[512 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            report(source.length(), source.length(), "下载完成，已保存到：下载/FastDL/" + source.getName());
            createShortcut(source.getName(), uri);
            openFile(uri, source.getName());
            return true;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            return false;
        }
    }

    private boolean publishToTree(File source, Uri treeUri) {
        Uri parent = treeUri;
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
            Uri uri = DocumentsContract.createDocument(getContentResolver(), parent, mime(source.getName()), source.getName());
            if (uri == null) return false;
            try (FileInputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("无法写入选择的文件夹");
                byte[] buffer = new byte[512 * 1024];
                for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
            }
            report(source.length(), source.length(), "下载完成，已保存到：选择的文件夹/" + source.getName());
            createShortcut(source.getName(), uri);
            openFile(uri, source.getName());
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void createShortcut(String name, Uri fileUri) {
        if (Build.VERSION.SDK_INT < 26 || fileUri == null) return;
        try {
            android.content.pm.ShortcutManager manager = getSystemService(android.content.pm.ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, mime(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "fastdl-" + Math.abs(name.hashCode()))
                    .setShortLabel(name.length() > 20 ? name.substring(0, 20) : name)
                    .setLongLabel("打开 " + name)
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_fastdl))
                    .setIntent(open).build();
            manager.requestPinShortcut(shortcut, null);
        } catch (Exception ignored) { }
    }

    private void openFile(Uri uri, String name) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(open);
        } catch (Exception ignored) {
            // The notification remains available so the user can open it manually.
        }
    }

    private static String mime(String name) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(name));
        return type == null ? "application/octet-stream" : type;
    }

    private void report(long done, long total, String text) {
        sendBroadcast(new Intent(ACTION_UPDATE)
                .setPackage(getPackageName()).putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total).putExtra(EXTRA_STATUS, text));
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(text, done, total));
    }


    private Notification notification(String text, long done, long total) {
        Intent pause = new Intent(this, DownloadService.class).setAction(ACTION_PAUSE);
        PendingIntent pauseIntent = PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("FastDL")
                .setContentText(text)
                .setOngoing(active != null)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "暂停", pauseIntent).build());
        if (total > 0) b.setProgress(100, (int) Math.min(100, done * 100 / total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "下载任务", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (active != null) active.pause(); worker.shutdownNow(); super.onDestroy(); }
}
