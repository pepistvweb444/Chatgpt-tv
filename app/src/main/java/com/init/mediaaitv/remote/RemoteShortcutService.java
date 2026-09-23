package com.init.mediaaitv.remote;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.init.mediaaitv.MainActivity;
import com.init.mediaaitv.capture.AudioCaptureService;

public final class RemoteShortcutService extends AccessibilityService {
    public static final String ACTION_SHOW_OVERLAY = "com.init.mediaaitv.SHOW_ACCESSIBILITY_OVERLAY";

    private final Handler handler = new Handler();
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams lp;
    private boolean collapsed = false;
    private TextView source;
    private TextView status;
    private Spinner target;
    private Button toggle;
    private BroadcastReceiver receiver;

    private final String[] labels = {
            "Español", "English", "Français", "Italiano", "Deutsch",
            "Português", "中文", "日本語", "한국어", "Euskara"
    };
    private final String[] codes = {
            "es-ES", "en-US", "fr-FR", "it-IT", "de-DE",
            "pt-PT", "zh-CN", "ja-JP", "ko-KR", "eu-ES"
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 700);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (ACTION_SHOW_OVERLAY.equals(intent.getAction())) {
                    if (overlay == null) showExpanded();
                    else refreshState();
                }
            }
        };
        IntentFilter overlayFilter = new IntentFilter(ACTION_SHOW_OVERLAY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, overlayFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, overlayFilter);
        }

        showExpanded();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        AudioCaptureService.lastStatus = "Barra Fire TV activa mediante Accesibilidad.";
    }

    private void showExpanded() {
        collapsed = false;
        replaceOverlay(buildExpanded());
    }

    private void showCollapsed() {
        collapsed = true;
        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER);
        mini.setPadding(16, 6, 16, 6);
        mini.setBackground(roundBg(Color.argb(240, 22, 23, 27), 36));

        Button b = button(AudioCaptureService.running ? "INIT ●" : "INIT ○");
        b.setTextColor(AudioCaptureService.running ? Color.rgb(215,255,79) : Color.WHITE);
        b.setOnClickListener(v -> showExpanded());
        mini.addView(b);
        replaceOverlay(mini);
    }

    private View buildExpanded() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(18, 8, 12, 8);
        bar.setBackground(roundBg(Color.argb(245, 22, 23, 27), 30));

        TextView logo = text("INIT", 18, Color.rgb(215,255,79));
        logo.setPadding(6,0,14,0);
        bar.addView(logo);

        source = text("Origen: Auto", 14, Color.WHITE);
        source.setPadding(0,0,10,0);
        bar.addView(source);

        TextView arrow = text("→", 17, Color.LTGRAY);
        arrow.setPadding(0,0,8,0);
        bar.addView(arrow);

        target = new Spinner(this);
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        target.setSelection(indexOf(prefs().getString("targetLang", "es-ES")));
        target.setMinimumWidth(180);
        target.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs().edit().putString("targetLang", codes[position]).apply();
                if (first) {
                    first = false;
                    return;
                }
                if (AudioCaptureService.running && status != null) {
                    status.setText("Idioma cambiado. Reinicia traduccion para aplicarlo.");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        bar.addView(target);

        toggle = button(AudioCaptureService.running ? "■ PARAR" : "▶ TRADUCIR");
        toggle.setMinWidth(170);
        toggle.setOnClickListener(v -> toggleTranslation());
        bar.addView(toggle);

        status = text("Listo", 12, Color.LTGRAY);
        status.setSingleLine(true);
        status.setPadding(10,0,10,0);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));

        Button minimize = button("—");
        minimize.setMinWidth(58);
        minimize.setOnClickListener(v -> showCollapsed());
        bar.addView(minimize);

        return bar;
    }

    private void toggleTranslation() {
        if (AudioCaptureService.running) {
            Intent stop = new Intent(this, AudioCaptureService.class)
                    .setAction(AudioCaptureService.ACTION_STOP);
            startService(stop);
            AudioCaptureService.lastStatus = "Traduccion detenida.";
            refreshState();
            return;
        }

        String lang = prefs().getString("targetLang", "es-ES");
        String server = prefs().getString("server", "http://165.22.83.150:8765");
        String voiceMode = prefs().getString("voiceMode", "clone");

        if (Build.VERSION.SDK_INT < 29) {
            Intent i = new Intent(this, AudioCaptureService.class);
            i.putExtra("legacyMic", true);
            i.putExtra("lang", lang);
            i.putExtra("quality", "Auto AI");
            i.putExtra("spatial", "Spatial AI automatico");
            i.putExtra("server", server);
            i.putExtra("voiceMode", voiceMode);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            AudioCaptureService.lastStatus = "Iniciando microfono Fire TV...";
        } else {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("shortcutStart", true);
            i.putExtra("overlayRequestedStart", true);
            startActivity(i);
            AudioCaptureService.lastStatus = "Autoriza captura de audio para traducir.";
        }
        refreshState();
    }

    private void refreshState() {
        if (overlay == null || collapsed) return;
        if (toggle != null) {
            toggle.setText(AudioCaptureService.running ? "■ PARAR" : "▶ TRADUCIR");
        }
        if (source != null) {
            String detected = AudioCaptureService.lastDetectedSourceLanguage;
            source.setText(detected == null || detected.isEmpty()
                    ? "Origen: Auto"
                    : "Origen: " + detected);
        }
        if (status != null) {
            String s = AudioCaptureService.lastStatus;
            status.setText(s == null || s.isEmpty() ? "Listo" : s);
        }
    }

    private void replaceOverlay(View view) {
        removeOverlay();
        overlay = view;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = collapsed ? 34 : 40;

        try {
            wm.addView(overlay, lp);
            if (collapsed) overlay.requestFocus();
            else if (toggle != null) toggle.requestFocus();
        } catch (Throwable t) {
            AudioCaptureService.lastStatus =
                    "Error barra Accesibilidad: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
        }
    }

    private void removeOverlay() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
            overlay = null;
        }
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("init", MODE_PRIVATE);
    }

    private int indexOf(String code) {
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(code)) return i;
        return 0;
    }

    private TextView text(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setMinHeight(50);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    private GradientDrawable roundBg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(1, Color.argb(100,255,255,255));
        return g;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        int k = event.getKeyCode();

        if (k == KeyEvent.KEYCODE_CAPTIONS || k == KeyEvent.KEYCODE_PROG_RED) {
            if (overlay == null || collapsed) showExpanded();
            else showCollapsed();
            return true;
        }

        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (overlay == null) showExpanded();
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacks(refresh);
        removeOverlay();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiver = null;
        }
        super.onDestroy();
    }
}
