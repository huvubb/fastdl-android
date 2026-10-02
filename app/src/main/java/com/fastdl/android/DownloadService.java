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
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;

/** Foreground owner for a download. Android may otherwise stop long network work. */
public final class DownloadService extends Service {
    public static final String ACTION_START = "com.fastdl.android.START";
    public static final String ACTION_PAUSE = "com.fastdl.android.PAUSE";
    public static final String ACTION_CANCEL = "com.fastdl.android.CANCEL";
    public static final String ACTION_UPDATE = "com.fastdl.android.UPDATE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_IMPROVEMENT_PLAN = "improvement_plan";
    private static final int NOTIFICATION_ID = 100;
    private static final String CHANNEL = "fastdl_downloads";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<QueuedDownload> queue = new ArrayDeque<>();
    private volatile HttpDownloader active;
    private volatile boolean batchStop;
    private volatile boolean running;
    private PowerManager.WakeLock downloadWakeLock;
    private long debugLastDone;
    private long debugLastAt;
    private long debugSpeed;
    private long debugPeakSpeed;
    private String debugResult = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            batchStop = true;
            synchronized (queue) { queue.clear(); }
            if (active != null) active.pause();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            batchStop = true;
            synchronized (queue) { queue.clear(); }
            if (active != null) active.cancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        ArrayList<String> urls = intent.getStringArrayListExtra(EXTRA_URLS);
        if (urls == null || urls.isEmpty()) {
            String url = intent.getStringExtra(EXTRA_URL);
            urls = url == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(url));
        }
        boolean improvementPlan = intent.getBooleanExtra(EXTRA_IMPROVEMENT_PLAN, false);
        if (urls.isEmpty()) return START_NOT_STICKY;
        DownloadOptions options = DownloadOptions.from(intent);
        String treeUri = intent.getStringExtra(EXTRA_TREE_URI);
        synchronized (queue) {
            for (String url : urls) queue.addLast(new QueuedDownload(url, options, treeUri, improvementPlan));
        }
        if (running) {
            report(0, 0, "已加入队列，等待当前下载完成");
            return START_NOT_STICKY;
        }
        batchStop = false;
        running = true;
        startForeground(NOTIFICATION_ID, notification("正在准备下载", 0, 0));
        acquireDownloadWakeLock();
        File base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        worker.execute(() -> {
            try {
                int index = 0;
                while (!batchStop) {
                    QueuedDownload item;
                    int waiting;
                    synchronized (queue) {
                        item = queue.pollFirst();
                        waiting = queue.size();
                    }
                    if (item == null) break;
                    index++;
                    String url = item.url;
                    debugLastDone = 0;
                    debugLastAt = System.currentTimeMillis();
                    debugSpeed = 0;
                    debugPeakSpeed = 0;
                    debugResult = "队列任务 " + index + "：正在准备下载（后方 " + waiting + " 个）";
                    report(0, 0, debugResult);
                    long startedAt = System.currentTimeMillis();
                    active = new HttpDownloader(base, url, item.options, new NetworkRouter(this, item.options.dualNetwork), this::report);
                    active.run();
                    File completed = newestCompleted(base, startedAt);
                    if (!batchStop && completed != null) {
                        // Explicit "open latest" avoids interrupting a batch with the app chooser.
                        if (item.treeUri == null || item.treeUri.isEmpty() || !publishToTree(completed, Uri.parse(item.treeUri))) {
                            publishToDownloads(completed);
                        }
                    }
                    if (item.improvementPlan) DebugReporter.send(url,
                            debugPeakSpeed > 0 ? debugPeakSpeed : debugSpeed, debugResult);
                }
            } finally {
                releaseDownloadWakeLock();
                active = null;
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void acquireDownloadWakeLock() {
        if (downloadWakeLock != null && downloadWakeLock.isHeld()) return;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) return;
        downloadWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastDL:download");
        downloadWakeLock.setReferenceCounted(false);
        downloadWakeLock.acquire();
    }

    private void releaseDownloadWakeLock() {
        if (downloadWakeLock != null && downloadWakeLock.isHeld()) downloadWakeLock.release();
        downloadWakeLock = null;
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
            rememberLastFile(uri, source.getName());
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
            rememberLastFile(uri, source.getName());
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void rememberLastFile(Uri uri, String name) {
        getSharedPreferences("fastdl-ui", MODE_PRIVATE).edit()
                .putString("last_file_uri", uri.toString())
                .putString("last_file_name", name).apply();
    }

    private static String mime(String name) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(name));
        return type == null ? "application/octet-stream" : type;
    }

    private void report(long done, long total, String text) {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(1, now - debugLastAt);
        debugSpeed = Math.max(0, (done - debugLastDone) * 1000 / elapsed);
        if (debugSpeed > debugPeakSpeed) debugPeakSpeed = debugSpeed;
        debugLastDone = done;
        debugLastAt = now;
        debugResult = text == null ? "" : text;
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

    private static final class QueuedDownload {
        final String url;
        final DownloadOptions options;
        final String treeUri;
        final boolean improvementPlan;

        QueuedDownload(String url, DownloadOptions options, String treeUri, boolean improvementPlan) {
            this.url = url;
            this.options = options;
            this.treeUri = treeUri;
            this.improvementPlan = improvementPlan;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (active != null) active.pause(); releaseDownloadWakeLock(); worker.shutdownNow(); super.onDestroy(); }
}
