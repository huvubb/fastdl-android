package com.fastdl.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves GitHub release pages to an actual downloadable asset URL. */
final class DownloadLinkResolver {
    private static final Pattern ASSET = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private DownloadLinkResolver() { }

    static String resolve(String input, NetworkRouter router) throws IOException {
        String value = input == null ? "" : input.trim();
        URI uri;
        try { uri = new URI(value); } catch (Exception ignored) { return value; }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) return value;
        String[] p = uri.getPath().split("/");
        if (p.length < 5 || !"releases".equals(p[3])) return value;
        String api;
        if ("tag".equals(p[4]) && p.length >= 6) {
            api = "https://api.github.com/repos/" + p[1] + "/" + p[2] + "/releases/tags/" + p[5];
        } else if ("latest".equals(p[4])) {
            api = "https://api.github.com/repos/" + p[1] + "/" + p[2] + "/releases/latest";
        } else if ("download".equals(p[4])) {
            return value;
        } else {
            return value;
        }
        HttpURLConnection connection = null;
        try {
            URL apiUrl = new URL(api);
            connection = router == null ? (HttpURLConnection) apiUrl.openConnection() : router.open(apiUrl);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "FastDL-Android/1.0");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return value;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null && body.length() < 512 * 1024;) body.append(line);
            }
            Matcher matcher = ASSET.matcher(body);
            while (matcher.find()) {
                String candidate = matcher.group(1).replace("\\/", "/");
                if (candidate.matches("(?i).*\\.(apk|zip|7z|rar|tar|gz)(\\?.*)?$")) return candidate;
            }
            matcher = ASSET.matcher(body);
            if (matcher.find()) return matcher.group(1).replace("\\/", "/");
            throw new IOException("GitHub Release 中没有可下载文件");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
