package com.init.mediaaitv.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.init.mediaaitv.MainActivity;
import com.init.mediaaitv.capture.AudioCaptureService;

public final class TranslationOverlayService extends Service {
    public static final String ACTION_SHOW = "com.init.mediaaitv.overlay.SHOW";
    public static final String ACTION_HIDE = "com.init.mediaaitv.overlay.HIDE";
    private static final String CHANNEL = "init_overlay";

    private final Handler handler = new Handler();
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View overlay;
    private boolean collapsed = false;
    private TextView source;
    private TextView status;
    private Spinner target;
    private Button toggle;

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

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(42, notification("Barra flotante INIT activa"));
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            openMainForOverlayPermission();
            return START_NOT_STICKY;
        }
        if (overlay == null) showExpanded();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        return START_STICKY;
    }

    private void showExpanded() {
        collapsed = false;
        replaceOverlay(buildExpanded());
    }

    private void showCollapsed() {
        collapsed = true;
        LinearLayout mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setGravity(Gravity.CENTER);
        mini.setPadding(18, 8, 18, 8);
        mini.setBackground(roundBg(Color.argb(235, 26, 27, 30), 40));

        Button b = button(AudioCaptureService.running ? "INIT  ●" : "INIT  ○");
        b.setTextColor(AudioCaptureService.running ? Color.rgb(215, 255, 79) : Color.WHITE);
        b.setOnClickListener(v -> showExpanded());
        mini.addView(b);
        installDrag(mini);
        replaceOverlay(mini);
    }

    private View buildExpanded() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(22, 10, 16, 10);
        bar.setBackground(roundBg(Color.argb(242, 22, 23, 27), 34));

        TextView logo = text("INIT", 19, Color.rgb(215, 255, 79));
        logo.setPadding(8, 0, 18, 0);
        bar.addView(logo);
        installDrag(logo);

        source = text("Origen: Auto", 15, Color.WHITE);
        source.setPadding(6, 0, 18, 0);
        bar.addView(source);

        TextView arrow = text("→", 18, Color.LTGRAY);
        arrow.setPadding(0, 0, 10, 0);
        bar.addView(arrow);

        target = new Spinner(this);
        target.setFocusable(true);
        target.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        String saved = prefs().getString("targetLang", "es-ES");
        int idx = indexOf(saved);
        target.setSelection(idx);
        target.setMinimumWidth(190);
        target.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs().edit().putString("targetLang", codes[position]).apply();
                if (first) {
                    first = false;
                    return;
                }
                if (AudioCaptureService.running) {
                    status.setText("Idioma cambiado. Pulsa PARAR y TRADUCIR para aplicarlo.");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        bar.addView(target);

        toggle = button(AudioCaptureService.running ? "■ PARAR" : "▶ TRADUCIR");
        toggle.setMinWidth(175);
        toggle.setOnClickListener(v -> toggleTranslation());
        bar.addView(toggle);

        status = text("Listo", 13, Color.LTGRAY);
        status.setSingleLine(true);
        status.setPadding(14, 0, 14, 0);
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(status, statLp);

        Button minimize = button("—");
        minimize.setMinWidth(64);
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
            startForegroundService(i);
            AudioCaptureService.lastStatus = "Iniciando captura del audio ambiente...";
        } else {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("shortcutStart", true);
            i.putExtra("overlayRequestedStart", true);
            startActivity(i);
            AudioCaptureService.lastStatus = "Autoriza la captura para iniciar la traduccion.";
        }
        refreshState();
    }

    private void refreshState() {
        if (overlay == null) return;
        if (collapsed) return;

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
        removeOverlayOnly();
        overlay = view;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = collapsed ? 36 : 42;
        try {
            wm.addView(overlay, lp);
            View focus = collapsed ? overlay : toggle;
            if (focus != null) focus.requestFocus();
        } catch (Throwable t) {
            AudioCaptureService.lastStatus = "No se pudo mostrar la barra flotante: " + t.getClass().getSimpleName();
            openMainForOverlayPermission();
        }
    }

    private void installDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + Math.round(e.getRawX() - sx);
                        lp.y = Math.max(0, oy - Math.round(e.getRawY() - sy));
                        try { wm.updateViewLayout(overlay, lp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void openMainForOverlayPermission() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("needOverlayPermission", true);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    private void removeOverlayOnly() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
            overlay = null;
        }
    }

    private void removeOverlay() {
        handler.removeCallbacks(refresh);
        removeOverlayOnly();
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
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
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setMinHeight(52);
        return b;
    }

    private GradientDrawable roundBg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(1, Color.argb(110, 255, 255, 255));
        return g;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "INIT floating translator",
                NotificationManager.IMPORTANCE_LOW
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
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("INIT Translator")
                .setContentText(text)
                .setContentIntent(p)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
