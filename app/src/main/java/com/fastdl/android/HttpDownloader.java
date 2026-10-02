package com.fastdl.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Dependency-free HTTP downloader. State is kept as independent part files, so a
 * process death or a user pause resumes safely without a database dependency.
 */
final class HttpDownloader {
    static {
        // Some mobile carriers expose IPv6 first even when their route to a
        // foreign CDN is black-holed. Prefer IPv4 so a dead AAAA route cannot
        // hold the probe at "connecting" while IPv4 is healthy.
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
        } catch (SecurityException ignored) { }
    }
    interface Listener { void update(long done, long total, String status); }
    // Large enough to keep high-bandwidth connections fed, while 128 active workers
    // remain within a modest memory envelope on phones.
    private static final int BUFFER = 512 * 1024;
    // Keep ranges large enough for GitHub's signed CDN, while giving phones
    // 8 effective transfers for a 30–50 MiB APK instead of only four.
    private static final long MIN_CHUNK = 4L * 1024 * 1024;
    private static final long MAX_CHUNK = 16L * 1024 * 1024;
    private static final long MEMORY_LIMIT = 8L * 1024 * 1024;
    private static final int DEFAULT_THREADS = 128;

    private final File downloadRoot;
    private String url;
    private final DownloadOptions options;
    private final NetworkRouter networkRouter;
    private final Listener listener;
    private final RateLimiter limiter;
    private final AdaptiveConcurrency concurrency;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicBoolean discard = new AtomicBoolean(false);
    private final AtomicLong done = new AtomicLong();
    private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
    private volatile long total;
    private volatile long lastReport;
    private long lastRateDone;
    private long lastRateTime;

    HttpDownloader(File downloadRoot, String url, DownloadOptions options, NetworkRouter networkRouter, Listener listener) {
        this.downloadRoot = downloadRoot;
        this.url = normalizeUrl(url);
        this.options = options == null ? new DownloadOptions(DEFAULT_THREADS, 0, "", new java.util.LinkedHashMap<>(), true) : options;
        this.networkRouter = networkRouter;
        this.limiter = new RateLimiter(this.options.limitBytesPerSecond);
        this.concurrency = new AdaptiveConcurrency(this.options.threads);
        this.listener = listener;
        this.lastRateTime = System.currentTimeMillis();
    }

    void pause() { stop.set(true); disconnectConnections(); }
    void cancel() { discard.set(true); stop.set(true); disconnectConnections(); }

    private void disconnectConnections() {
        synchronized (connections) {
            for (HttpURLConnection connection : connections) connection.disconnect();
            connections.clear();
        }
    }

    void run() {
        try {
            if (!downloadRoot.exists() && !downloadRoot.mkdirs()) throw new IOException("无法创建下载目录");
            report(0, "正在获取下载地址…");
            url = DownloadLinkResolver.resolve(url, networkRouter);
            Probe probe = probe();
            if (probe.finalUrl != null && !probe.finalUrl.isEmpty()) url = probe.finalUrl;
            total = probe.size;
            String name = safeName(probe.name);
            File finalFile = new File(downloadRoot, name);
            if (probe.size > 0 && finalFile.isFile() && finalFile.length() == probe.size) {
                report(probe.size, "文件已完整存在：" + name);
                return;
            }
            if (canUseMemory(probe.size)) {
                memoryStream(finalFile, name, probe.size);
                return;
            }
            if (!probe.range || probe.size <= 0) {
                singleStream(finalFile, name);
                return;
            }
            ranged(probe, finalFile, name);
        } catch (Exception e) {
            report(done.get(), stop.get() ? (discard.get() ? "下载已取消" : "下载已暂停，可继续") : "下载失败：" + clean(e));
        }
    }

    /** Small files avoid part-file I/O; large files always stay on disk to prevent OOM. */
    private boolean canUseMemory(long size) {
        if (size <= 0 || size > Integer.MAX_VALUE) return false;
        // Keep the requested memory-loading mode, but do not let it bypass
        // multi-range acceleration for APKs and other medium-sized files.
        long safe = Math.min(MEMORY_LIMIT, Runtime.getRuntime().maxMemory() / 4);
        return size <= safe;
    }

    private void memoryStream(File finalFile, String name, long size) throws IOException {
        report(0, "内存载入：" + pretty(size) + "，完成后一次写入磁盘");
        HttpURLConnection c = open(null);
        ByteArrayOutputStream memory = new ByteArrayOutputStream((int) size);
        File temp = new File(downloadRoot, name + ".memory.tmp");
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             BufferedOutputStream disk = new BufferedOutputStream(new FileOutputStream(temp), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            for (int n; !stop.get() && (n = in.read(buffer)) >= 0;) {
                limiter.acquire(n); memory.write(buffer, 0, n); disk.write(buffer, 0, n); done.addAndGet(n); reportMaybe();
            }
        } finally { close(c); }
        if (discard.get()) { report(0, "下载已取消"); return; }
        if (stop.get()) { report(done.get(), "内存载入已暂停，请重新开始"); return; }
        if (memory.size() != size) throw new IOException("内存载入数据不完整");
        diskSync(temp);
        if (finalFile.exists() && !finalFile.delete()) throw new IOException("无法替换同名文件");
        if (!temp.renameTo(finalFile)) throw new IOException("无法完成文件改名");
        report(size, "内存载入完成：" + name);
    }

    private static void diskSync(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.getFD().sync(); }
    }

    private Probe probe() throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4 && !stop.get(); attempt++) {
            try {
                report(0, attempt == 0 ? "连接服务器…" : "连接失败，正在重试（" + (attempt + 1) + "/4）…");
                return probeOnce();
            } catch (IOException error) {
                last = error;
                if (attempt < 3) {
                    try { Thread.sleep(700L << attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last == null ? new IOException("连接已取消") : last;
    }

    private Probe probeOnce() throws IOException {
        HttpURLConnection c = open("bytes=0-0");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            throw new IOException("服务器响应 " + code);
        }
        long size = -1;
        boolean range = code == HttpURLConnection.HTTP_PARTIAL;
        String contentRange = c.getHeaderField("Content-Range");
        if (contentRange != null && contentRange.contains("/")) {
            try { size = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1)); } catch (NumberFormatException ignored) { }
        }
        if (size < 0) size = c.getContentLengthLong();
        String filename = filename(c, url);
        String etag = c.getHeaderField("ETag");
        String finalUrl = c.getURL() == null ? url : c.getURL().toString();
        close(c);
        return new Probe(url, Math.max(size, -1), range, filename, etag == null ? "" : etag,
                finalUrl.equals(url) ? "" : finalUrl);
    }

    private void ranged(Probe probe, File finalFile, String name) throws Exception {
        File work = new File(downloadRoot, name + ".fastdl");
        File meta = new File(work, "meta.properties");
        if (work.exists() && !sameTransfer(meta, probe)) deleteTree(work);
        if (!work.exists() && !work.mkdirs()) throw new IOException("无法创建临时目录");
        saveMeta(meta, probe);

        long chunk = chooseChunkSize(url, probe.size, options.threads);
        int chunks = (int) ((probe.size + chunk - 1) / chunk);
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            long expected = Math.min(chunk, probe.size - i * chunk);
            File part = part(work, i);
            long have = part.isFile() ? part.length() : 0;
            if (have > expected) { if (!part.delete()) throw new IOException("无法重置损坏分片"); have = 0; }
            done.addAndGet(have);
            if (have < expected) pending.add(i);
        }
        report(done.get(), "极速模式：" + pending.size() + " 个分片待完成，" + cdnProfile(url)
                + "，最高 " + options.threads + " 路并发");
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.threads, Math.max(1, pending.size())));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index : pending) futures.add(pool.submit(() -> fetchPart(work, index, chunk, probe.size)));
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        if (discard.get()) { deleteTree(work); report(0, "下载已取消，临时文件已删除"); return; }
        if (stop.get()) { report(done.get(), "下载已暂停，可重新开始继续"); return; }
        report(done.get(), "正在合并文件…");
        File assembling = new File(work, "assembled.tmp");
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(assembling), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            for (int i = 0; i < chunks; i++) {
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(part(work, i)), BUFFER)) {
                    for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
                }
            }
        }
        if (assembling.length() != probe.size) throw new IOException("合并后文件大小不符");
        if (finalFile.exists() && !finalFile.delete()) throw new IOException("无法替换同名文件");
        if (!assembling.renameTo(finalFile)) throw new IOException("无法完成文件改名");
        deleteTree(work);
        report(probe.size, "下载完成：" + name);
    }

    private void fetchPart(File work, int index, long chunk, long size) {
        long start = index * chunk;
        long end = Math.min(size - 1, start + chunk - 1);
        File target = part(work, index);
        for (int attempt = 0; attempt < 4 && !stop.get(); attempt++) {
            long have = target.exists() ? target.length() : 0;
            if (start + have > end) return;
            if (!concurrency.acquire(stop)) return;
            HttpURLConnection c = null;
            try {
                c = open("bytes=" + (start + have) + "-" + end);
                if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) throw new IOException("分片服务器未返回 206");
                try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
                     BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target, true), BUFFER)) {
                    byte[] buf = new byte[BUFFER];
                    for (int n; !stop.get() && (n = in.read(buf)) >= 0;) {
                        limiter.acquire(n);
                        out.write(buf, 0, n);
                        done.addAndGet(n);
                        reportMaybe();
                    }
                }
                if (!stop.get() && target.length() != end - start + 1) throw new IOException("分片长度不足");
                return;
            } catch (IOException ignored) {
                int lowered = concurrency.backOff();
                if (lowered > 0) report(done.get(), "连接受限，自动降至 " + lowered + " 路并发");
                if (attempt == 3) throw new RuntimeException("分片 " + (index + 1) + " 下载失败");
                try { Thread.sleep(250L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            } finally { if (c != null) close(c); concurrency.release(); }
        }
    }

    private void singleStream(File finalFile, String name) throws IOException {
        File partial = new File(downloadRoot, name + ".partial");
        if (discard.get() && partial.exists()) partial.delete();
        report(0, "服务器不支持分片，使用单连接下载");
        HttpURLConnection c = open(null);
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial, false), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            for (int n; !stop.get() && (n = in.read(buf)) >= 0;) {
                limiter.acquire(n);
                out.write(buf, 0, n);
                done.addAndGet(n);
                reportMaybe();
            }
        } finally { close(c); }
        if (discard.get()) { partial.delete(); report(0, "下载已取消，临时文件已删除"); return; }
        if (stop.get()) { report(done.get(), "下载已暂停（该服务器不支持断点续传）"); return; }
        if (finalFile.exists() && !finalFile.delete()) throw new IOException("无法替换同名文件");
        if (!partial.renameTo(finalFile)) throw new IOException("无法完成文件改名");
        report(done.get(), "下载完成：" + name);
    }

    private HttpURLConnection open(String range) throws IOException {
        URL target = new URL(url);
        HttpURLConnection c;
        if (options.proxy.isEmpty()) c = networkRouter == null ? (HttpURLConnection) target.openConnection() : networkRouter.open(target);
        else {
            URI p = URI.create(options.proxy);
            String host = p.getHost();
            int port = p.getPort();
            if (host == null || port <= 0) throw new IOException("代理格式应为 http://主机:端口 或 socks://主机:端口");
            Proxy.Type type = p.getScheme() != null && p.getScheme().toLowerCase(java.util.Locale.US).startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            c = (HttpURLConnection) target.openConnection(new Proxy(type, new InetSocketAddress(host, port)));
        }
        c.setConnectTimeout(8_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "keep-alive");
        // Do not force a CDN revalidation on every range. Immutable release
        // assets are already content-addressed/signed; no-transform preserves
        // byte ranges without defeating the edge cache.
        c.setRequestProperty("Cache-Control", "no-transform");
        c.setRequestProperty("Accept", "application/octet-stream,*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (!options.headers.containsKey("User-Agent")) c.setRequestProperty("User-Agent", "FastDL-Android/0.2");
        for (java.util.Map.Entry<String, String> header : options.headers.entrySet()) c.setRequestProperty(header.getKey(), header.getValue());
        if (range != null) c.setRequestProperty("Range", range);
        connections.add(c);
        return c;
    }

    private void close(HttpURLConnection c) {
        if (c != null) {
            connections.remove(c);
            // Closing the response stream is enough for normal completion and
            // lets HttpURLConnection reuse the keep-alive socket. Explicit
            // disconnect remains in pause()/cancel() via disconnectConnections().
        }
    }

    /** GitHub's web file pages are HTML; convert blob links to the actual raw file. */
    private static String normalizeUrl(String input) {
        if (input == null) return "";
        try {
            URI source = new URI(input.trim());
            if (!"github.com".equalsIgnoreCase(source.getHost())) return input.trim();
            String[] parts = source.getPath().split("/");
            if (parts.length >= 6 && "blob".equals(parts[3])) {
                StringBuilder raw = new StringBuilder("https://raw.githubusercontent.com/")
                        .append(parts[1]).append('/').append(parts[2]).append('/').append(parts[4]);
                for (int i = 5; i < parts.length; i++) raw.append('/').append(parts[i]);
                return raw.toString();
            }
        } catch (Exception ignored) { }
        return input.trim();
    }

    private static long chooseChunkSize(String target, long size, int threads) {
        String host = hostOf(target);
        long min = MIN_CHUNK;
        long max = MAX_CHUNK;
        if (host.contains("cloudfront.net") || host.contains("cloudflare")
                || host.contains("akamai") || host.contains("akamaized.net")
                || host.contains("fastly.net")) {
            min = 8L * 1024 * 1024;
            max = 32L * 1024 * 1024;
        } else if (host.contains("jsdelivr.net") || host.contains("unpkg.com")) {
            min = 2L * 1024 * 1024;
            max = 8L * 1024 * 1024;
        }
        long wanted = (size + Math.max(1, threads) - 1) / Math.max(1, threads);
        return Math.max(min, Math.min(max, wanted));
    }

    private static String cdnProfile(String target) {
        String host = hostOf(target);
        if (host.contains("github") || host.contains("githubusercontent")) return "GitHub CDN";
        if (host.contains("cloudfront.net")) return "CloudFront";
        if (host.contains("cloudflare")) return "Cloudflare";
        if (host.contains("akamai") || host.contains("akamaized.net")) return "Akamai";
        if (host.contains("fastly.net")) return "Fastly";
        if (host.contains("jsdelivr.net") || host.contains("unpkg.com")) return "JS CDN";
        return "通用 CDN";
    }

    private static String hostOf(String target) {
        try {
            String host = new URI(target).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.US);
        } catch (Exception ignored) { return ""; }
    }

    private void reportMaybe() {
        long now = System.currentTimeMillis();
        if (now - lastReport <= 500) return;
        synchronized (this) {
            if (now - lastReport <= 500) return;
            long current = done.get();
            long elapsed = Math.max(1, now - lastRateTime);
            long rate = Math.max(0, (current - lastRateDone) * 1000 / elapsed);
            lastReport = now; lastRateTime = now; lastRateDone = current;
            report(current, "下载中：" + pretty(current) + (total > 0 ? " / " + pretty(total) : "") + " · " + pretty(rate) + "/s · " + concurrency.limit() + "路");
        }
    }
    private void report(long current, String status) { listener.update(current, total, status); }
    private static File part(File work, int index) { return new File(work, String.format("part-%05d", index)); }
    private static String pretty(long bytes) { return bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0); }
    private static String clean(Exception e) { String s = e.getMessage(); return s == null ? e.getClass().getSimpleName() : s; }

    private static boolean sameTransfer(File meta, Probe p) {
        try (FileInputStream in = new FileInputStream(meta)) {
            Properties props = new Properties(); props.load(in);
            return p.url.equals(props.getProperty("url")) && String.valueOf(p.size).equals(props.getProperty("size")) && p.etag.equals(props.getProperty("etag"));
        } catch (IOException e) { return false; }
    }
    private static void saveMeta(File file, Probe p) throws IOException {
        Properties props = new Properties(); props.setProperty("url", p.url); props.setProperty("size", String.valueOf(p.size)); props.setProperty("etag", p.etag);
        try (FileOutputStream out = new FileOutputStream(file)) { props.store(out, "FastDL transfer metadata"); }
    }
    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IOException("无法清理临时文件");
    }
    private static String filename(HttpURLConnection c, String url) {
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) { int n = cd.toLowerCase().indexOf("filename="); if (n >= 0) return cd.substring(n + 9).replace("\"", "").trim(); }
        try { String path = new URI(url).getPath(); String name = path.substring(path.lastIndexOf('/') + 1); return name.isEmpty() ? "download.bin" : name; }
        catch (Exception ignored) { return "download.bin"; }
    }
    private static String safeName(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("[\\r\\n]", "").trim();
        return name.isEmpty() ? "download.bin" : name.substring(0, Math.min(name.length(), 120));
    }
    /** Shared global token bucket: the limit applies to all part workers together. */
    private static final class RateLimiter {
        private final long limit;
        private double tokens;
        private long last = System.nanoTime();
        RateLimiter(long limit) { this.limit = limit; this.tokens = limit; }
        void acquire(int requested) {
            if (limit <= 0 || requested <= 0) return;
            synchronized (this) {
            int remaining = requested;
            while (remaining > 0) {
                long now = System.nanoTime();
                tokens = Math.min(limit, tokens + (now - last) / 1_000_000_000d * limit);
                last = now;
                int granted = (int) Math.min(remaining, Math.floor(tokens));
                if (granted > 0) { tokens -= granted; remaining -= granted; continue; }
                long waitMs = Math.max(1, (long) Math.ceil(1000d / limit));
                try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            }
        }
    }
    /** Caps simultaneous sockets and backs off in powers of two on transport failures. */
    private static final class AdaptiveConcurrency {
        private int limit;
        private int running;
        AdaptiveConcurrency(int initial) { limit = Math.max(1, initial); }
        synchronized boolean acquire(AtomicBoolean stop) {
            while (!stop.get() && running >= limit) {
                try { wait(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            if (stop.get()) return false;
            running++;
            return true;
        }
        synchronized void release() { if (running > 0) running--; notifyAll(); }
        synchronized int backOff() {
            if (limit <= 16) return 0;
            limit = Math.max(16, limit / 2);
            notifyAll();
            return limit;
        }
        synchronized int limit() { return limit; }
    }
    private static final class Probe {
        final long size; final boolean range; final String name; final String etag; final String url; final String finalUrl;
        Probe(String url, long size, boolean range, String name, String etag, String finalUrl) {
            this.url = url; this.size = size; this.range = range; this.name = name; this.etag = etag; this.finalUrl = finalUrl;
        }
    }
}
