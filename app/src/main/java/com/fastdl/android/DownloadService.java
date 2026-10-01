package com.fastdl.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

import java.io.File;
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
        worker.execute(() -> {
            try {
                active.run();
            } finally {
                active = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
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
