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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.ArrayList;

/** A deliberately focused download screen: people paste a URL and the engine chooses the rest. */
public final class MainActivity extends Activity {
    private EditText urlInput;
    private TextView status;
    private TextView percent;
    private TextView speed;
    private ProgressBar progress;
    private TextView promptText;
    private TextView hintText;
    private TextView monitorTitleText;
    private TextView noteText;
    private TextView debugLabelText;
    private TextView palette;
    private SharedPreferences preferences;
    private LinearLayout root;
    private LinearLayout hero;
    private LinearLayout linkCard;
    private LinearLayout monitor;
    private ImageView mark;
    private Button startButton;
    private Button folderButton;
    private Button openLatestButton;
    private Button updateButton;
    private LinearLayout advancedPanel;
    private final ArrayList<Button> glassButtons = new ArrayList<>();
    private CheckBox improvementPlan;
    private Spinner debugThreads;
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
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 11);
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
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(18));
        scroll.addView(root);

        hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(22), dp(18), dp(22), dp(18));
        hero.setBackgroundResource(R.drawable.bg_hero);
        mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_fastdl);
        mark.setPadding(dp(8), dp(8), dp(8), dp(8));
        hero.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(14), 0, 0, 0);
        TextView title = text("FastDL", 30, Color.WHITE);
        title.setLetterSpacing(0.02f);
        TextView subtitle = text("FAST · SIMPLE · RELIABLE", 11, Color.rgb(220, 235, 255));
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

        space(root, 14);
        linkCard = new LinearLayout(this);
        linkCard.setOrientation(LinearLayout.VERTICAL);
        linkCard.setPadding(dp(20), dp(20), dp(20), dp(18));
        promptText = text("下载链接", 19, Color.rgb(27, 40, 64));
        hintText = text("每行一个链接 · 下载时可继续添加到队尾", 12, Color.rgb(117, 132, 154));
        hintText.setPadding(0, dp(4), 0, dp(12));
        linkCard.addView(promptText);
        linkCard.addView(hintText);
        urlInput = new EditText(this);
        urlInput.setHint("粘贴一个或多个下载链接，每行一个");
        urlInput.setTextSize(15);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setMaxLines(4);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackgroundResource(R.drawable.bg_url);
        urlInput.setPadding(dp(14), 0, dp(14), 0);
        linkCard.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(82)));
        space(linkCard, 14);
        startButton = new Button(this);
        startButton.setAllCaps(false);
        startButton.setText("开始下载 / 加入队列");
        startButton.setTextSize(17);
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundResource(R.drawable.bg_primary);
        startButton.setOnClickListener(v -> startDownload());
        linkCard.addView(startButton, new LinearLayout.LayoutParams(-1, dp(54)));
        folderButton = secondary("保存到：系统下载");
        folderButton.setOnClickListener(v -> chooseFolder());
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(-1, dp(44));
        folderParams.topMargin = dp(8);
        linkCard.addView(folderButton, folderParams);
        Button advanced = secondary("高级设置 · 128 路连接");
        advanced.setTextSize(13);
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(-1, dp(40));
        advancedParams.topMargin = dp(6);
        linkCard.addView(advanced, advancedParams);
        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        debugLabelText = text("下载线程（本机测速选择）", 12, Color.rgb(85, 111, 151));
        debugLabelText.setPadding(0, dp(7), 0, 0);
        advancedPanel.addView(debugLabelText);
        debugThreads = new Spinner(this);
        String[] threadChoices = {"8 路", "16 路", "32 路", "64 路", "128 路"};
        ArrayAdapter<String> threadAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, threadChoices);
        debugThreads.setAdapter(threadAdapter);
        debugThreads.setSelection(4);
        advancedPanel.addView(debugThreads, new LinearLayout.LayoutParams(-1, dp(40)));
        improvementPlan = new CheckBox(this);
        improvementPlan.setText("加入用户改进计划（可随时关闭）");
        improvementPlan.setTextSize(12);
        improvementPlan.setTextColor(Color.rgb(85, 111, 151));
        improvementPlan.setChecked(preferences.getBoolean("improvement_plan", false));
        improvementPlan.setPadding(0, dp(6), 0, 0);
        improvementPlan.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("improvement_plan", checked).apply());
        advancedPanel.addView(improvementPlan, new LinearLayout.LayoutParams(-1, dp(36)));
        linkCard.addView(advancedPanel);
        advanced.setOnClickListener(v -> {
            boolean show = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            advanced.setText(show ? "收起高级设置" : "高级设置 · " + debugThreads.getSelectedItem() + "连接");
        });
        root.addView(linkCard);

        space(root, 12);
        monitor = new LinearLayout(this);
        monitor.setOrientation(LinearLayout.VERTICAL);
        monitor.setPadding(dp(20), dp(17), dp(20), dp(17));
        monitor.setBackgroundResource(R.drawable.bg_status);
        LinearLayout monitorHeader = new LinearLayout(this);
        monitorHeader.setGravity(Gravity.CENTER_VERTICAL);
        monitorTitleText = text("传输进度", 16, Color.rgb(47, 80, 128));
        percent = text("0%", 17, Color.rgb(25, 92, 192));
        percent.setGravity(Gravity.RIGHT);
        monitorHeader.addView(monitorTitleText, new LinearLayout.LayoutParams(0, -2, 1));
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
        openLatestButton = secondary("打开最近下载");
        openLatestButton.setTextSize(15);
        openLatestButton.setOnClickListener(v -> openLatestFile());
        root.addView(openLatestButton, new LinearLayout.LayoutParams(-1, dp(48)));

        space(root, 10);
        updateButton = secondary("检查更新");
        updateButton.setOnClickListener(v -> checkForUpdate());
        root.addView(updateButton, new LinearLayout.LayoutParams(-1, dp(44)));

        space(root, 10);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button pause = secondary("暂停并清空队列");
        pause.setOnClickListener(v -> sendAction(DownloadService.ACTION_PAUSE));
        Button cancel = secondary("取消全部任务");
        cancel.setOnClickListener(v -> sendAction(DownloadService.ACTION_CANCEL));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(48), 1);
        first.setMargins(0, 0, dp(8), 0);
        controls.addView(pause, first);
        controls.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(controls);

        noteText = text("文件保存至 下载/FastDL · 完成后可直接打开", 12, Color.rgb(120, 132, 150));
        noteText.setGravity(Gravity.CENTER);
        noteText.setLineSpacing(dp(3), 1f);
        noteText.setPadding(dp(10), dp(18), dp(10), 0);
        root.addView(noteText);
        applyPalette(preferences.getBoolean("pink", false));
        setContentView(scroll);
    }

    private Button secondary(String title) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(50, 84, 136));
        button.setBackgroundResource(R.drawable.bg_secondary);
        glassButtons.add(button);
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
        ArrayList<String> urls = new ArrayList<>();
        for (String item : urlInput.getText().toString().split("\\r?\\n")) {
            String url = item.trim();
            if (url.startsWith("http://") || url.startsWith("https://")) urls.add(url);
        }
        if (urls.isEmpty()) {
            status.setText("请输入有效的下载链接");
            return;
        }
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START);
        int threads = DownloadOptions.AUTO_THREADS;
        try { threads = Integer.parseInt(String.valueOf(debugThreads.getSelectedItem()).replaceAll("\\D+", "")); }
        catch (Exception ignored) { }
        i.putExtra(DownloadService.EXTRA_URL, urls.get(0))
                .putStringArrayListExtra(DownloadService.EXTRA_URLS, urls)
                .putExtra(DownloadOptions.EXTRA_THREADS, threads)
                .putExtra(DownloadOptions.EXTRA_DUAL_NETWORK, true)
                .putExtra(DownloadService.EXTRA_IMPROVEMENT_PLAN,
                        preferences.getBoolean("improvement_plan", false));
        String tree = preferences.getString("download_tree", "");
        if (!tree.isEmpty()) i.putExtra(DownloadService.EXTRA_TREE_URI, tree);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        urlInput.setText("");
        status.setText("已加入 " + urls.size() + " 个任务；可继续粘贴链接加入队列");
    }

    private void chooseFolder() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, 42);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 42 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
        catch (Exception ignored) { }
        preferences.edit().putString("download_tree", uri.toString()).apply();
        if (folderButton != null) folderButton.setText("保存到：已选择文件夹");
    }

    private void sendAction(String action) {
        startService(new Intent(this, DownloadService.class).setAction(action));
    }

    private void openLatestFile() {
        String raw = preferences.getString("last_file_uri", "");
        String name = preferences.getString("last_file_name", "");
        if (raw.isEmpty()) {
            status.setText("还没有已完成的下载文件");
            return;
        }
        try {
            Uri uri = Uri.parse(raw);
            String type = getContentResolver().getType(uri);
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    type == null ? "application/octet-stream" : type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(open);
        } catch (Exception error) {
            status.setText("无法打开 " + name + "；文件可能已被移动或删除");
        }
    }

    private void checkForUpdate() {
        updateButton.setEnabled(false);
        updateButton.setText("正在检查更新…");
        status.setText("正在向 GitHub 查询最新版本");
        new Thread(() -> {
            try {
                UpdateChecker.Result result = UpdateChecker.latest(this);
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateButton.setText("检查更新");
                    if (!result.isNewer) {
                        status.setText(result.currentVersion.equals(result.version)
                                ? "已是最新版本（" + result.currentVersion + "）"
                                : "本机 " + result.currentVersion + " 高于 GitHub 发行版 " + result.version);
                        return;
                    }
                    status.setText("发现 " + result.version + "，正在下载更新包");
                    updateButton.setText("正在下载 " + result.version);
                    downloadAndInstallUpdate(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateButton.setText("检查更新");
                    status.setText("检查更新失败：" + safeMessage(error));
                });
            }
        }, "fastdl-update-check").start();
    }

    private void downloadAndInstallUpdate(UpdateChecker.Result result) {
        new Thread(() -> {
            try {
                Uri apk = UpdateChecker.download(this, result.apkUrl, result.version,
                        (done, total) -> runOnUiThread(() -> updateButton.setText(
                                total > 0 ? "下载更新 " + (done * 100 / total) + "%" : "正在下载更新")));
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateButton.setText("检查更新");
                    status.setText("更新包已下载，正在打开系统安装器");
                    UpdateChecker.install(this, apk);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    updateButton.setText("检查更新");
                    status.setText("更新下载失败：" + safeMessage(error));
                });
            }
        }, "fastdl-update-download").start();
    }

    private static String safeMessage(Exception error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? "请检查网络后重试" : text;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void applyPalette(boolean pink) {
        // The themes intentionally use different temperature and contrast, not merely
        // a swapped accent: ice-blue is cool and airy; berry-peach is warm and soft.
        int start = Color.rgb(pink ? 232 : 66, pink ? 67 : 97, pink ? 151 : 233);
        int end = Color.rgb(pink ? 255 : 67, pink ? 157 : 207, pink ? 116 : 248);
        root.setBackgroundResource(pink ? R.drawable.bg_glass_pink : R.drawable.bg_glass_blue);

        GradientDrawable heroGradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(145, Color.red(start), Color.green(start), Color.blue(start)),
                        Color.argb(105, Color.red(end), Color.green(end), Color.blue(end))});
        heroGradient.setCornerRadius(dp(34));
        heroGradient.setStroke(dp(1), Color.argb(175, 255, 255, 255));
        hero.setBackground(heroGradient);
        GradientDrawable cardGlass = glass(Color.argb(62,
                pink ? 255 : 239, pink ? 245 : 248, pink ? 250 : 255), dp(28));
        linkCard.setBackground(cardGlass);
        GradientDrawable statusGlass = glass(Color.argb(56,
                pink ? 255 : 230, pink ? 229 : 246, pink ? 243 : 255), dp(26));
        monitor.setBackground(statusGlass);
        GradientDrawable inputGlass = glass(Color.argb(42,
                pink ? 255 : 247, pink ? 250 : 252, pink ? 252 : 255), dp(20));
        urlInput.setBackground(inputGlass);
        // Every control uses the same glass material. The primary action gets
        // hierarchy from weight and type, not from an opaque colored block.
        GradientDrawable actionGlass = glass(Color.argb(46,
                pink ? 255 : 247, pink ? 235 : 251, pink ? 245 : 255), dp(20));
        startButton.setBackground(actionGlass);
        startButton.setTextColor(primaryTextColor(pink));
        GradientDrawable palettePill = glass(Color.argb(28, 255, 255, 255), dp(18));
        palette.setBackground(palettePill);
        palette.setText(pink ? "蓝色" : "粉色");
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(start));
        int secondaryText = Color.rgb(pink ? 133 : 37, pink ? 52 : 84, pink ? 97 : 151);
        int primaryText = primaryTextColor(pink);
        promptText.setTextColor(primaryText);
        hintText.setTextColor(secondaryText);
        status.setTextColor(primaryText);
        speed.setTextColor(secondaryText);
        percent.setTextColor(pink ? Color.rgb(191, 45, 105) : Color.rgb(24, 91, 190));
        monitorTitleText.setTextColor(secondaryText);
        noteText.setTextColor(secondaryText);
        debugLabelText.setTextColor(secondaryText);
        improvementPlan.setTextColor(secondaryText);
        urlInput.setTextColor(primaryText);
        urlInput.setHintTextColor(Color.argb(180, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText)));
        for (Button button : glassButtons) {
            button.setTextColor(secondaryText);
            button.setBackground(glass(Color.argb(38,
                    pink ? 255 : 241, pink ? 250 : 248, pink ? 252 : 255), dp(20)));
        }
        if (mark != null) mark.setImageResource(pink ? R.drawable.ic_fastdl_pink : R.drawable.ic_fastdl);
        if (improvementPlan != null) improvementPlan.setButtonTintList(
                android.content.res.ColorStateList.valueOf(start));
    }

    private GradientDrawable glass(int fill, int radius) {
        GradientDrawable material = new GradientDrawable();
        int alpha = Color.alpha(fill);
        int red = Color.red(fill), green = Color.green(fill), blue = Color.blue(fill);
        // A vertical highlight + tinted lower edge simulates light entering and
        // refracting through the material while keeping it GPU-cheap.
        material.setColors(new int[]{
                Color.argb(Math.min(210, alpha + 70), 255, 255, 255),
                fill,
                Color.argb(Math.max(35, alpha - 40), red, green, blue)
        });
        material.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        material.setCornerRadius(radius);
        material.setStroke(dp(1), Color.argb(145, 255, 255, 255));
        return material;
    }

    private int primaryTextColor(boolean pink) {
        return Color.rgb(pink ? 86 : 20, pink ? 30 : 58, pink ? 59 : 106);
    }

    private static String pretty(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(0, bytes / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
