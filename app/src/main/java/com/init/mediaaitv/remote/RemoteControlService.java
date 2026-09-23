package com.init.mediaaitv.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.init.mediaaitv.MainActivity;
import com.init.mediaaitv.capture.AudioCaptureService;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteControlService extends Service {
    public static final int PORT = 8766;
    private static final String CHANNEL = "init_remote";
    private static final int NOTIF_ID = 43;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean stopped = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, notification("Mando remoto disponible"));
        ensurePin();
        pool = Executors.newCachedThreadPool();
        pool.execute(this::serverLoop);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            while (!stopped) {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            }
        } catch (Throwable t) {
            if (!stopped) {
                AudioCaptureService.lastStatus =
                        "Mando remoto no disponible: " + t.getClass().getSimpleName();
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            s.setSoTimeout(3000);
            String first = in.readLine();
            if (first == null || first.trim().isEmpty()) return;

            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            // Drain headers.
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {}

            Map<String,String> params = parseQuery(query);

            if ("/hello".equals(path)) {
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("name", "INIT Media AI TV");
                obj.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                obj.put("port", PORT);
                obj.put("pin_required", true);
                replyJson(out, 200, obj);
                return;
            }

            if (!getPin().equals(params.get("pin"))) {
                JSONObject obj = new JSONObject();
                obj.put("ok", false);
                obj.put("error", "bad-pin");
                replyJson(out, 403, obj);
                return;
            }

            if ("/status".equals(path)) {
                replyJson(out, 200, statusJson());
                return;
            }

            if ("/start".equals(path) && "POST".equalsIgnoreCase(method)) {
                String lang = params.getOrDefault("lang", "es-ES");
                String voice = "fast".equals(params.get("voice")) ? "fast" : "clone";
                getSharedPreferences("init", MODE_PRIVATE).edit()
                        .putString("targetLang", lang)
                        .putString("voiceMode", voice)
                        .apply();

                Runnable starter = () -> startTranslation(lang, voice);
                if (AudioCaptureService.running) {
                    startService(new Intent(this, AudioCaptureService.class)
                            .setAction(AudioCaptureService.ACTION_STOP));
                    main.postDelayed(starter, 450);
                } else {
                    main.post(starter);
                }

                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("starting", true);
                obj.put("lang", lang);
                obj.put("voice", voice);
                replyJson(out, 200, obj);
                return;
            }

            if ("/stop".equals(path) && "POST".equalsIgnoreCase(method)) {
                startService(new Intent(this, AudioCaptureService.class)
                        .setAction(AudioCaptureService.ACTION_STOP));
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                replyJson(out, 200, obj);
                return;
            }

            JSONObject obj = new JSONObject();
            obj.put("ok", false);
            obj.put("error", "not-found");
            replyJson(out, 404, obj);

        } catch (Throwable ignored) {
        }
    }

    private void startTranslation(String lang, String voice) {
        String server = getSharedPreferences("init", MODE_PRIVATE)
                .getString("server", "http://165.22.83.150:8765");

        if (Build.VERSION.SDK_INT < 29) {
            Intent i = new Intent(this, AudioCaptureService.class);
            i.putExtra("legacyMic", true);
            i.putExtra("lang", lang);
            i.putExtra("quality", "Auto AI");
            i.putExtra("spatial", "Spatial AI automatico");
            i.putExtra("server", server);
            i.putExtra("voiceMode", voice);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            AudioCaptureService.lastStatus = "Inicio remoto: capturando audio Fire TV...";
        } else {
            // MediaProjection consent cannot be silently granted by a companion app.
            AudioCaptureService.lastStatus =
                    "Abre INIT en la TV para autorizar la captura de audio.";
        }
    }

    private JSONObject statusJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("ok", true);
        obj.put("running", AudioCaptureService.running);
        obj.put("status", AudioCaptureService.lastStatus);
        obj.put("source_language", AudioCaptureService.lastDetectedSourceLanguage);
        obj.put("target_language", getSharedPreferences("init", MODE_PRIVATE)
                .getString("targetLang", "es-ES"));
        obj.put("voice_mode", getSharedPreferences("init", MODE_PRIVATE)
                .getString("voiceMode", "clone"));
        obj.put("api", Build.VERSION.SDK_INT);
        obj.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        return obj;
    }

    private Map<String,String> parseQuery(String q) {
        HashMap<String,String> out = new HashMap<>();
        if (q == null || q.isEmpty()) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {}
            out.put(k, v);
        }
        return out;
    }

    private void replyJson(BufferedWriter out, int code, JSONObject obj) throws Exception {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 " + code + (code == 200 ? " OK" : " ERROR") + "\r\n");
        out.write("Content-Type: application/json; charset=utf-8\r\n");
        out.write("Content-Length: " + bytes.length + "\r\n");
        out.write("Connection: close\r\n\r\n");
        out.flush();
        sWrite(out, obj.toString());
    }

    private void sWrite(BufferedWriter out, String text) throws Exception {
        out.write(text);
        out.flush();
    }

    private void ensurePin() {
        if (!getSharedPreferences("init", MODE_PRIVATE).contains("remotePin")) {
            int n = 100000 + new SecureRandom().nextInt(900000);
            getSharedPreferences("init", MODE_PRIVATE).edit()
                    .putString("remotePin", String.valueOf(n))
                    .apply();
        }
    }

    private String getPin() {
        ensurePin();
        return getSharedPreferences("init", MODE_PRIVATE)
                .getString("remotePin", "000000");
    }

    public static String localIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress()
                            && a instanceof java.net.Inet4Address
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "sin IP";
    }

    public static String currentPin(android.content.Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("init", MODE_PRIVATE);
        String pin = p.getString("remotePin", null);
        if (pin == null) {
            int n = 100000 + new SecureRandom().nextInt(900000);
            pin = String.valueOf(n);
            p.edit().putString("remotePin", pin).apply();
        }
        return pin;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "INIT Remote", NotificationManager.IMPORTANCE_LOW
        ));
    }

    private Notification notification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("INIT Remote")
                .setContentText(text)
                .setContentIntent(p)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        stopped = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
