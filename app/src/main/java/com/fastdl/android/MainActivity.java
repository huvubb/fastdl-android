package com.fastdl.android;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native, dependency-free screen. Settings stay intentionally compact for lower memory use. */
public final class MainActivity extends Activity {
    private EditText urlInput, threadsInput, limitInput, proxyInput, headersInput;
    private CheckBox dualNetwork;
    private TextView status;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, 0);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, 0);
            status.setText(intent.getStringExtra(DownloadService.EXTRA_STATUS));
            progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int) Math.min(100, done * 100 / total));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fastdl", MODE_PRIVATE);
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
    @Override public void onStop() { unregisterReceiver(updates); super.onStop(); }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(root);

        TextView title = label("FastDL", 28); title.setTextColor(Color.WHITE); title.setBackgroundResource(R.drawable.bg_hero); root.addView(title);
        TextView subtitle = label("极速直链下载 · 内存加速 · 自动续传", 14); subtitle.setTextColor(Color.WHITE); subtitle.setBackgroundResource(R.drawable.bg_hero); root.addView(subtitle);
        urlInput = field("下载链接（HTTP / HTTPS）", InputType.TYPE_TEXT_VARIATION_URI, ""); root.addView(urlInput);
        threadsInput = field("并发连接数（1–128，默认 128；自动降档）", InputType.TYPE_CLASS_NUMBER, prefs.getString("threads", "128")); root.addView(threadsInput);
        limitInput = field("总限速（可选：5M、512K；留空不限速）", InputType.TYPE_CLASS_TEXT, prefs.getString("limit", "")); root.addView(limitInput);
        proxyInput = field("代理（可选：http://127.0.0.1:7890）", InputType.TYPE_TEXT_VARIATION_URI, prefs.getString("proxy", "")); root.addView(proxyInput);
        dualNetwork = new CheckBox(this); dualNetwork.setText("双网加速（同时使用 WLAN 与移动数据，消耗移动流量）"); dualNetwork.setChecked(prefs.getBoolean("dual", false)); root.addView(dualNetwork);
        headersInput = field("请求头（可选，每行 KEY: VALUE；不会保存）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, "");
        headersInput.setMinLines(3); headersInput.setGravity(Gravity.TOP); root.addView(headersInput);

        Button start = new Button(this); start.setText("开始 / 继续下载"); start.setTextColor(Color.WHITE); start.setBackgroundResource(R.drawable.bg_primary); start.setOnClickListener(v -> startDownload()); root.addView(start);
        LinearLayout controls = new LinearLayout(this);
        Button pause = new Button(this); pause.setText("暂停并保留进度"); pause.setBackgroundResource(R.drawable.bg_secondary); pause.setOnClickListener(v -> sendAction(DownloadService.ACTION_PAUSE));
        Button cancel = new Button(this); cancel.setText("取消并删除"); cancel.setBackgroundResource(R.drawable.bg_secondary); cancel.setOnClickListener(v -> sendAction(DownloadService.ACTION_CANCEL));
        controls.addView(pause, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(controls);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setIndeterminate(false);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(14)));
        status = label("等待下载任务", 14); status.setGravity(Gravity.CENTER_HORIZONTAL); status.setPadding(0, pad, 0, 0); root.addView(status);
        root.addView(label("文件保存在应用专属 Downloads 目录。Cookie、Referer、User-Agent 可直接填进请求头。", 12));
        setContentView(scroll);
    }

    private EditText field(String hint, int type, String value) {
        EditText view = new EditText(this);
        view.setHint(hint); view.setText(value); view.setInputType(type);
        view.setBackgroundResource(R.drawable.bg_input);
        view.setSingleLine(type != InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return view;
    }
    private TextView label(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); return v; }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) { status.setText("仅支持 HTTP / HTTPS 链接"); return; }
        int threads;
        try { threads = Integer.parseInt(threadsInput.getText().toString().trim()); } catch (NumberFormatException e) { threads = 128; }
        threads = Math.max(1, Math.min(128, threads));
        prefs.edit().putString("threads", String.valueOf(threads)).putString("limit", limitInput.getText().toString().trim())
                .putString("proxy", proxyInput.getText().toString().trim()).putBoolean("dual", dualNetwork.isChecked()).apply();
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_URL, url).putExtra(DownloadOptions.EXTRA_THREADS, threads)
                .putExtra(DownloadOptions.EXTRA_LIMIT, limitInput.getText().toString())
                .putExtra(DownloadOptions.EXTRA_PROXY, proxyInput.getText().toString())
                .putExtra(DownloadOptions.EXTRA_DUAL_NETWORK, dualNetwork.isChecked())
                .putExtra(DownloadOptions.EXTRA_HEADERS, headersInput.getText().toString());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
    private void sendAction(String action) { startService(new Intent(this, DownloadService.class).setAction(action)); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
}
