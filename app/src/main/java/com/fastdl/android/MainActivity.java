package com.fastdl.android;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.SharedPreferences;

/** A deliberately focused download screen: people paste a URL and the engine chooses the rest. */
public final class MainActivity extends Activity {
    private EditText urlInput;
    private TextView status;
    private TextView percent;
    private TextView speed;
    private ProgressBar progress;
    private TextView palette;
    private SharedPreferences preferences;
    private LinearLayout hero;
    private Button startButton;
    private long lastSpeedDone;
    private long lastSpeedAt;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, 0);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, 0);
            status.setText(intent.getStringExtra(DownloadService.EXTRA_STATUS));
            progress.setIndeterminate(total <= 0);
            long now = System.currentTimeMillis();
            long elapsed = lastSpeedAt == 0 ? 0 : Math.max(1, now - lastSpeedAt);
            long bytesPerSecond = elapsed == 0 ? 0 : Math.max(0, (done - lastSpeedDone) * 1000 / elapsed);
            lastSpeedAt = now;
            lastSpeedDone = done;
            speed.setText("当前速度  " + pretty(bytesPerSecond) + "/s");
            if (total > 0) {
                int value = (int) Math.min(100, done * 100 / total);
                progress.setProgress(value);
                percent.setText(value + "%");
            } else {
                percent.setText("准备中");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("fastdl-ui", MODE_PRIVATE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
    }

    @Override public void onStop() {
        unregisterReceiver(updates);
        super.onStop();
    }

    private void buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_app);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, pad);
        scroll.addView(root);

        hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(22));
        hero.setBackgroundResource(R.drawable.bg_hero);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_fastdl);
        mark.setPadding(dp(8), dp(8), dp(8), dp(8));
        hero.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(14), 0, 0, 0);
        TextView title = text("FastDL", 27, Color.WHITE);
        title.setLetterSpacing(0.02f);
        TextView subtitle = text("极速直链下载", 14, Color.rgb(213, 231, 255));
        heroText.addView(title);
        heroText.addView(subtitle);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        palette = text(preferences.getBoolean("pink", false) ? "蓝色" : "粉色", 13, Color.WHITE);
        palette.setGravity(Gravity.CENTER);
        palette.setPadding(dp(10), dp(8), dp(10), dp(8));
        palette.setOnClickListener(v -> {
            boolean pink = !preferences.getBoolean("pink", false);
            preferences.edit().putBoolean("pink", pink).apply();
            palette.setText(pink ? "蓝色" : "粉色");
            applyPalette(pink);
        });
        hero.addView(palette, new LinearLayout.LayoutParams(-2, -2));
        root.addView(hero);

        space(root, 18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.bg_card);
        TextView prompt = text("粘贴下载链接", 17, Color.rgb(25, 42, 70));
        TextView hint = text("支持 HTTP、HTTPS 和 GitHub 文件链接，连接与分片策略自动选择。", 13, Color.rgb(104, 119, 142));
        hint.setPadding(0, dp(4), 0, dp(14));
        card.addView(prompt);
        card.addView(hint);
        urlInput = new EditText(this);
        urlInput.setHint("https://example.com/file.zip");
        urlInput.setTextSize(15);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackgroundResource(R.drawable.bg_url);
        urlInput.setPadding(dp(14), 0, dp(14), 0);
        card.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(56)));
        space(card, 14);
        startButton = new Button(this);
        startButton.setAllCaps(false);
        startButton.setText("开始下载");
        startButton.setTextSize(16);
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundResource(R.drawable.bg_primary);
        startButton.setOnClickListener(v -> startDownload());
        card.addView(startButton, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(card);
        applyPalette(preferences.getBoolean("pink", false));

        space(root, 14);
        LinearLayout monitor = new LinearLayout(this);
        monitor.setOrientation(LinearLayout.VERTICAL);
        monitor.setPadding(dp(18), dp(16), dp(18), dp(16));
        monitor.setBackgroundResource(R.drawable.bg_status);
        LinearLayout monitorHeader = new LinearLayout(this);
        monitorHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView monitorTitle = text("下载状态 · 自动加速", 14, Color.rgb(47, 80, 128));
        percent = text("0%", 17, Color.rgb(25, 92, 192));
        percent.setGravity(Gravity.RIGHT);
        monitorHeader.addView(monitorTitle, new LinearLayout.LayoutParams(0, -2, 1));
        monitorHeader.addView(percent, new LinearLayout.LayoutParams(-2, -2));
        status = text("等待下载任务", 15, Color.rgb(25, 42, 70));
        status.setPadding(0, dp(7), 0, dp(3));
        speed = text("当前速度  0 KB/s", 13, Color.rgb(85, 111, 151));
        speed.setPadding(0, 0, 0, dp(12));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 110, 224)));
        monitor.addView(monitorHeader);
        monitor.addView(status);
        monitor.addView(speed);
        monitor.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        root.addView(monitor);

        space(root, 14);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button pause = secondary("暂停");
        pause.setOnClickListener(v -> sendAction(DownloadService.ACTION_PAUSE));
        Button cancel = secondary("取消任务");
        cancel.setOnClickListener(v -> sendAction(DownloadService.ACTION_CANCEL));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(48), 1);
        first.setMargins(0, 0, dp(8), 0);
        controls.addView(pause, first);
        controls.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(controls);

        TextView note = text("自动使用最多 128 路连接；网络不稳定时会平稳降档。下载完成后文件保存在应用 Downloads 目录。", 12, Color.rgb(120, 132, 150));
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(10), dp(18), dp(10), 0);
        root.addView(note);
        setContentView(scroll);
    }

    private Button secondary(String title) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(50, 84, 136));
        button.setBackgroundResource(R.drawable.bg_secondary);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void space(LinearLayout parent, int height) {
        View view = new View(this);
        parent.addView(view, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.setText("请输入有效的下载链接");
            return;
        }
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_URL, url)
                .putExtra(DownloadOptions.EXTRA_THREADS, DownloadOptions.AUTO_THREADS)
                .putExtra(DownloadOptions.EXTRA_DUAL_NETWORK, true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void sendAction(String action) {
        startService(new Intent(this, DownloadService.class).setAction(action));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void applyPalette(boolean pink) {
        int start = Color.rgb(pink ? 224 : 20, pink ? 72 : 89, pink ? 135 : 217);
        int end = Color.rgb(pink ? 248 : 40, pink ? 119 : 126, pink ? 166 : 242);
        GradientDrawable heroGradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        heroGradient.setCornerRadius(dp(24));
        hero.setBackground(heroGradient);
        GradientDrawable actionGradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        actionGradient.setCornerRadius(dp(16));
        startButton.setBackground(actionGradient);
    }

    private static String pretty(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(0, bytes / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
