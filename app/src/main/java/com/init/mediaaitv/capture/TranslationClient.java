package com.init.mediaaitv.capture;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public final class TranslationClient {
    private static final String TAG = "InitTranslationClient";
    private final String baseUrl;
    private final String language;
    private final String quality;
    private final String spatial;
    private volatile String lastMode = "unknown";
    private volatile String lastError = "";

    public TranslationClient(String baseUrl, String language, String quality, String spatial) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        this.baseUrl = clean;
        this.language = language;
        this.quality = quality;
        this.spatial = spatial;
    }

    public byte[] translatePcm(byte[] pcm) {
        if (baseUrl.isEmpty()) {
            lastError = "empty-server";
            return new byte[0];
        }
        HttpURLConnection c = null;
        try {
            String q = "?lang=" + URLEncoder.encode(language, "UTF-8")
                    + "&quality=" + URLEncoder.encode(quality, "UTF-8")
                    + "&spatial=" + URLEncoder.encode(spatial, "UTF-8");
            URL url = new URL(baseUrl + "/v1/translate-pcm" + q);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(180000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/L16;rate=48000;channels=1");
            c.setRequestProperty("X-Init-AI", "tv-v0.7");
            c.setFixedLengthStreamingMode(pcm.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(pcm);
            }

            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "http-" + code;
                return new byte[0];
            }

            lastMode = c.getHeaderField("X-Init-Mode");
            if (lastMode == null) lastMode = "unknown";
            lastError = "";

            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "Translation endpoint unavailable: " + e.getMessage());
            return new byte[0];
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public String getLastMode() {
        return lastMode;
    }

    public String getLastError() {
        return lastError;
    }
}
