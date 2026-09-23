package com.init.mediaaitv.capture;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class TranslationClient {
    private static final String TAG = "InitTranslationClient";

    private final String baseUrl;
    private final String language;
    private final String quality;
    private final String spatial;
    private final String voiceMode;
    private final String sessionId;

    private volatile String lastMode = "unknown";
    private volatile String lastError = "";
    private volatile String lastQueuedSeconds = "0";
    private volatile String lastSourceLanguage = "";

    public TranslationClient(
            String baseUrl,
            String language,
            String quality,
            String spatial,
            String voiceMode
    ) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        this.baseUrl = clean;
        this.language = language;
        this.quality = quality;
        this.spatial = spatial;
        this.voiceMode = "clone".equals(voiceMode) ? "clone" : "fast";
        this.sessionId = UUID.randomUUID().toString();
    }

    public boolean pushPcm(byte[] pcm) {
        if (baseUrl.isEmpty()) {
            lastError = "empty-server";
            return false;
        }
        HttpURLConnection c = null;
        try {
            String q = "?lang=" + URLEncoder.encode(language, "UTF-8")
                    + "&quality=" + URLEncoder.encode(quality, "UTF-8")
                    + "&spatial=" + URLEncoder.encode(spatial, "UTF-8")
                    + "&voice_mode=" + URLEncoder.encode(voiceMode, "UTF-8");

            URL url = new URL(baseUrl + "/v1/stream/push" + q);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/L16;rate=48000;channels=1");
            c.setRequestProperty("X-Init-AI", "tv-v1.0");
            c.setRequestProperty("X-Init-Session", sessionId);
            c.setFixedLengthStreamingMode(pcm.length);

            try (OutputStream out = c.getOutputStream()) {
                out.write(pcm);
            }

            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "push-http-" + code;
                return false;
            }

            lastError = "";
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[1024];
                while (in.read(buf) >= 0) {
                    // Drain response.
                }
            }
            return true;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "Push endpoint unavailable: " + e.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public String pollText() {
        if (baseUrl.isEmpty()) return "";

        HttpURLConnection c = null;
        try {
            String q = "?session=" + URLEncoder.encode(sessionId, "UTF-8");
            URL url = new URL(baseUrl + "/v1/stream/poll-text" + q);

            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setRequestMethod("GET");
            c.setRequestProperty("X-Init-AI", "tv-v1.0");
            c.setRequestProperty("X-Init-Session", sessionId);

            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "poll-text-http-" + code;
                return "";
            }

            String body;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                body = out.toString(StandardCharsets.UTF_8.name());
            }

            JSONObject obj = new JSONObject(body);
            lastMode = obj.optString("mode", "unknown");
            lastQueuedSeconds = String.valueOf(obj.optDouble("queued_seconds", 0));
            lastSourceLanguage = obj.optString("source_lang", "");
            lastError = obj.optString("error", "");
            return obj.optString("text", "");

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "Text poll failed: " + e.getMessage());
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public byte[] pollPcm() {
        if (baseUrl.isEmpty()) return new byte[0];

        HttpURLConnection c = null;
        try {
            String q = "?session=" + URLEncoder.encode(sessionId, "UTF-8");
            URL url = new URL(baseUrl + "/v1/stream/poll" + q);

            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setRequestMethod("GET");
            c.setRequestProperty("X-Init-AI", "tv-v1.0");
            c.setRequestProperty("X-Init-Session", sessionId);

            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "poll-http-" + code;
                return new byte[0];
            }

            lastMode = c.getHeaderField("X-Init-Mode");
            if (lastMode == null) lastMode = "unknown";

            String queued = c.getHeaderField("X-Init-Queued-Seconds");
            if (queued != null) lastQueuedSeconds = queued;

            String sourceLang = c.getHeaderField("X-Init-Source-Language");
            lastSourceLanguage = sourceLang == null ? "" : sourceLang;

            String backendError = c.getHeaderField("X-Init-Error");
            lastError = backendError == null ? "" : backendError;

            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            }

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "Audio poll failed: " + e.getMessage());
            return new byte[0];
        } finally {
            if (c != null) c.disconnect();
        }
    }


    public byte[] synthesizeText(String text) {
        if (baseUrl.isEmpty() || text == null || text.trim().isEmpty()) return new byte[0];
        HttpURLConnection c = null;
        try {
            String q = "?lang=" + URLEncoder.encode(language, "UTF-8");
            URL url = new URL(baseUrl + "/v1/synthesize-text" + q);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(180000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Init-Session", sessionId);
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "tts-http-" + code;
                return new byte[0];
            }
            String backendError = c.getHeaderField("X-Init-Error");
            lastError = backendError == null ? "" : backendError;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "Fallback synthesis failed: " + e.getMessage());
            return new byte[0];
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public void stopSession() {
        if (baseUrl.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            String q = "?session=" + URLEncoder.encode(sessionId, "UTF-8");
            URL url = new URL(baseUrl + "/v1/session/stop" + q);

            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Init-Session", sessionId);
            c.getResponseCode();
        } catch (Exception ignored) {
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

    public String getLastQueuedSeconds() {
        return lastQueuedSeconds;
    }

    public String getLastSourceLanguage() {
        return lastSourceLanguage;
    }

    public String getVoiceMode() {
        return voiceMode;
    }
}
