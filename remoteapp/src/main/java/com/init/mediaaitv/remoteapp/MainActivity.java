package com.init.mediaaitv.remoteapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PORT = 8766;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private EditText ip;
    private EditText pin;
    private Spinner lang;
    private Spinner voice;
    private TextView state;
    private TextView detected;
    private Button start;
    private Button stop;
    private Button refresh;
    private Button scan;

    private final String[] langLabels = {
            "Español", "English", "Français", "Italiano", "Deutsch",
            "Português", "中文", "日本語", "한국어", "Euskara"
    };
    private final String[] langCodes = {
            "es-ES", "en-US", "fr-FR", "it-IT", "de-DE",
            "pt-PT", "zh-CN", "ja-JP", "ko-KR", "eu-ES"
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restore();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17,18,21));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 34, 34, 50);
        scroll.addView(root);

        root.addView(label("INIT REMOTE", 30, Color.rgb(215,255,79)));
        root.addView(label("Control remoto para INIT Media AI TV", 17, Color.WHITE));

        ip = field("IP o dirección del Fire TV, p. ej. 192.168.1.50 o 192.168.1.50:8766");
        pin = field("PIN de 6 dígitos que aparece en INIT TV");
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(ip);
        root.addView(pin);

        scan = button("BUSCAR FIRE TV EN LA RED");
        root.addView(scan);

        root.addView(label("Idioma destino", 16, Color.LTGRAY));
        lang = new Spinner(this);
        lang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langLabels));
        root.addView(lang);

        root.addView(label("Modo de voz", 16, Color.LTGRAY));
        voice = new Spinner(this);
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Voces originales multi-hablante", "Rápido"}));
        root.addView(voice);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        start = button("▶ TRADUCIR");
        stop = button("■ PARAR");
        refresh = button("ACTUALIZAR");
        controls.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);

        state = label("Sin conectar", 16, Color.WHITE);
        detected = label("Origen: Auto", 16, Color.rgb(215,255,79));
        root.addView(state);
        root.addView(detected);

        root.addView(label(
                "El Fire TV y este móvil deben estar en la misma red. "
                        + "Abre INIT una vez en la TV para activar el receptor remoto.",
                14,
                Color.LTGRAY
        ));

        scan.setOnClickListener(v -> scanNetwork());
        refresh.setOnClickListener(v -> refreshStatus());
        start.setOnClickListener(v -> startTranslation());
        stop.setOnClickListener(v -> stopTranslation());

        setContentView(scroll);
    }

    private void scanNetwork() {
        scan.setEnabled(false);
        state.setText("Buscando INIT TV...");
        io.execute(() -> {
            String local = localIpv4();
            if (local == null) {
                ui.post(() -> {
                    state.setText("No se encontró una red local IPv4.");
                    scan.setEnabled(true);
                });
                return;
            }

            int dot = local.lastIndexOf('.');
            if (dot < 0) {
                ui.post(() -> {
                    state.setText("No se pudo determinar la subred.");
                    scan.setEnabled(true);
                });
                return;
            }

            String prefix = local.substring(0, dot + 1);
            AtomicBoolean found = new AtomicBoolean(false);
            ExecutorService probes = Executors.newFixedThreadPool(32);
            List<Runnable> jobs = new ArrayList<>();

            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                if (host.equals(local)) continue;
                jobs.add(() -> {
                    if (found.get()) return;
                    try {
                        JSONObject hello = getJson("http://" + host + ":" + PORT + "/hello", 180, 300);
                        if (hello.optBoolean("ok") && "INIT Media AI TV".equals(hello.optString("name"))) {
                            if (found.compareAndSet(false, true)) {
                                ui.post(() -> {
                                    ip.setText(host);
                                    getPreferences(MODE_PRIVATE).edit().putString("ip", host).apply();
                                    state.setText("Encontrado: " + hello.optString("device", "Fire TV"));
                                    scan.setEnabled(true);
                                });
                            }
                        }
                    } catch (Throwable ignored) {}
                });
            }

            for (Runnable job : jobs) probes.submit(job);
            probes.shutdown();

            long end = System.currentTimeMillis() + 9000;
            while (!found.get() && System.currentTimeMillis() < end) {
                try { Thread.sleep(120); } catch (InterruptedException e) { break; }
            }
            if (!found.get()) {
                probes.shutdownNow();
                ui.post(() -> {
                    state.setText("No se encontró INIT TV. Comprueba que la app esté abierta en la TV.");
                    scan.setEnabled(true);
                });
            }
        });
    }

    private void startTranslation() {
        save();
        String target = langCodes[lang.getSelectedItemPosition()];
        String mode = voice.getSelectedItemPosition() == 1 ? "fast" : "clone";
        post(
                "/start?pin=" + enc(pin.getText().toString().trim())
                        + "&lang=" + enc(target)
                        + "&voice=" + enc(mode),
                "Iniciando traducción..."
        );
    }

    private void stopTranslation() {
        save();
        post("/stop?pin=" + enc(pin.getText().toString().trim()), "Deteniendo...");
    }

    private void refreshStatus() {
        save();
        final String host = ip.getText().toString().trim();
        final String p = pin.getText().toString().trim();
        if (host.isEmpty() || p.isEmpty()) {
            state.setText("Introduce IP y PIN.");
            return;
        }

        final String base;
        try {
            base = normalizeBaseUrl(host);
        } catch (IllegalArgumentException e) {
            state.setText("Dirección del Fire TV no válida.");
            return;
        }

        state.setText("Consultando " + base + "...");
        io.execute(() -> {
            try {
                JSONObject obj = getJson(
                        base + "/status?pin=" + enc(p),
                        1200,
                        1800
                );
                ui.post(() -> renderStatus(obj));
            } catch (Throwable t) {
                ui.post(() -> state.setText("No conecta con la TV: " + t.getClass().getSimpleName()));
            }
        });
    }

    private void post(String path, String pending) {
        final String host = ip.getText().toString().trim();
        if (host.isEmpty()) {
            state.setText("Introduce o busca la IP del Fire TV.");
            return;
        }

        final String base;
        try {
            base = normalizeBaseUrl(host);
        } catch (IllegalArgumentException e) {
            state.setText("Dirección del Fire TV no válida.");
            return;
        }

        state.setText(pending);
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new java.net.URL(
                        base + path
                ).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(2500);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
                int code = c.getResponseCode();
                String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                JSONObject obj = new JSONObject(body);
                ui.post(() -> {
                    if (code == 403) state.setText("PIN incorrecto.");
                    else if (!obj.optBoolean("ok")) state.setText("Error: " + obj.optString("error"));
                    else {
                        state.setText("Comando enviado.");
                        ui.postDelayed(this::refreshStatus, 700);
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> state.setText("No conecta con la TV: " + t.getClass().getSimpleName()));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void renderStatus(JSONObject obj) {
        if (!obj.optBoolean("ok")) {
            if ("bad-pin".equals(obj.optString("error"))) state.setText("PIN incorrecto.");
            else state.setText("Error: " + obj.optString("error"));
            return;
        }

        boolean running = obj.optBoolean("running");
        String text = obj.optString("status", "");
        String src = obj.optString("source_language", "");
        String target = obj.optString("target_language", "es-ES");
        state.setText((running ? "● ACTIVA · " : "○ PARADA · ") + text);
        detected.setText(src.isEmpty()
                ? "Origen: Auto → " + target
                : "Origen: " + src + " → " + target);
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("empty");

        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);

        if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        } else if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }

        // The remote receiver is plain HTTP on the local network.
        // Accept IP, host:port, or a pasted URL without duplicating :8766.
        String host = value;
        String port = String.valueOf(PORT);

        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);

        if (host.startsWith("[") && host.contains("]")) {
            int close = host.indexOf(']');
            String ipv6 = host.substring(0, close + 1);
            String rest = host.substring(close + 1);
            if (rest.startsWith(":") && rest.length() > 1) port = rest.substring(1);
            host = ipv6;
        } else {
            int firstColon = host.indexOf(':');
            int lastColon = host.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                String possiblePort = host.substring(lastColon + 1);
                if (!possiblePort.isEmpty()) {
                    for (int i = 0; i < possiblePort.length(); i++) {
                        if (!Character.isDigit(possiblePort.charAt(i))) {
                            throw new IllegalArgumentException("bad-port");
                        }
                    }
                    port = possiblePort;
                    host = host.substring(0, lastColon);
                }
            }
        }

        if (host.isEmpty()) throw new IllegalArgumentException("bad-host");
        return "http://" + host + ":" + port;
    }

    private JSONObject getJson(String url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            JSONObject obj = new JSONObject(body);
            if (code >= 400) return obj;
            return obj;
        } finally {
            c.disconnect();
        }
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "{}";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress()
                            && a instanceof Inet4Address
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void restore() {
        ip.setText(getPreferences(MODE_PRIVATE).getString("ip", ""));
        pin.setText(getPreferences(MODE_PRIVATE).getString("pin", ""));
    }

    private void save() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("ip", ip.getText().toString().trim())
                .putString("pin", pin.getText().toString().trim())
                .apply();
    }

    private String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(8, 12, 8, 12);
        return v;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setPadding(10, 14, 10, 14);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(56);
        return b;
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
