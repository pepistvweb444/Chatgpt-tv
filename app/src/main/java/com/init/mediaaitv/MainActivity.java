package com.init.mediaaitv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.init.mediaaitv.capture.AudioCaptureService;
import com.init.mediaaitv.launcher.TvApps;
import com.init.mediaaitv.ui.DeviceCapabilities;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 900;
    private static final int REQ_AUDIO = 901;

    private Spinner lang;
    private Spinner quality;
    private Spinner spatial;
    private Spinner voiceMode;
    private Spinner apps;
    private EditText server;
    private TextView status;
    private List<TvApps.Item> appItems;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 902);
        }
        if (getIntent().getBooleanExtra("shortcutStart", false)) {
            status.setText("Pulsa Iniciar traduccion para renovar el permiso de captura de Android.");
        }
    }

    private TextView text(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(8, 8, 8, 8);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(16);
        b.setMinHeight(58);
        b.setFocusable(true);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a);
        s.setFocusable(true);
        return s;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(16, 18, 20));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(44, 28, 44, 40);
        sv.addView(root);

        root.addView(text("INIT MEDIA AI TV", 30, Color.rgb(215, 255, 79)));
        root.addView(text("Traduccion de voz + mejora audiovisual adaptativa", 18, Color.WHITE));
        root.addView(text(DeviceCapabilities.summary(this), 14, Color.LTGRAY));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        lang = spinner(new String[]{
                "Espanol (es-ES)",
                "English (en-US)",
                "Francais (fr-FR)",
                "Italiano (it-IT)",
                "Deutsch (de-DE)",
                "Portugues (pt-PT)",
                "Chinese (zh-CN)",
                "Japanese (ja-JP)",
                "Korean (ko-KR)",
                "Euskara (eu-ES)"
        });
        quality = spinner(new String[]{"Auto AI", "4K AI", "8K AI", "Original / baja latencia"});
        spatial = spinner(new String[]{"Spatial AI automatico", "Binaural auriculares", "5.1 / 7.1", "Original"});
        voiceMode = spinner(new String[]{"Rapido continuo (voz IA local)", "Voces originales multi-hablante (recomendado)"});
        voiceMode.setSelection(1);

        row1.addView(lang, new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(quality, new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(spatial, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row1);
        root.addView(text("Modo de voz", 18, Color.WHITE));
        root.addView(voiceMode);

        server = new EditText(this);
        server.setHint("Servidor IA de DigitalOcean");
        server.setTextColor(Color.WHITE);
        server.setHintTextColor(Color.GRAY);
        server.setText(load("server", "http://165.22.83.150:8765"));
        server.setSingleLine(true);
        server.setTextSize(16);
        root.addView(server);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("Iniciar traduccion");
        Button stop = button("Parar");
        Button access = button("Activar boton del mando");
        actions.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(access, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        root.addView(text("Aplicaciones instaladas", 20, Color.WHITE));
        appItems = TvApps.list(this);
        apps = new Spinner(this);
        apps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, appItems));
        apps.setFocusable(true);
        root.addView(apps);

        Button open = button("Abrir aplicacion seleccionada");
        root.addView(open);

        status = text(
                "Listo. La captura solo funciona con aplicaciones que Android permita capturar. "
                + "El modo 8K AI esta preparado para nuestro reproductor/servidor y para futuras integraciones OEM; "
                + "el video DRM seguro no puede ser interceptado por una APK normal.",
                15,
                Color.LTGRAY
        );
        root.addView(status);

        root.addView(text(
                "Perfiles incluidos: traduccion con referencia de voz autorizada, restauracion 4K/8K AI, HDR/denoise/deblur, "
                + "interpolacion opcional, separacion voz/musica/FX, audio espacial y ruta de baja latencia para directo/juegos.",
                14,
                Color.GRAY
        ));

        start.setOnClickListener(v -> requestCapture());
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, AudioCaptureService.class).setAction(AudioCaptureService.ACTION_STOP);
            startService(i);
            status.setText("Traduccion detenida.");
        });
        access.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            status.setText("Activa INIT Media AI TV en Accesibilidad para usar CC/rojo como acceso rapido.");
        });
        open.setOnClickListener(v -> {
            if (!appItems.isEmpty()) {
                TvApps.Item it = appItems.get(apps.getSelectedItemPosition());
                boolean ok = TvApps.launch(this, it.pkg);
                status.setText(ok
                        ? "Abriendo " + it.label + ". INIT seguira activo si esa app permite captura de audio."
                        : "No se pudo abrir la app.");
            }
        });

        setContentView(sv);
    }

    private void requestCapture() {
        save("server", server.getText().toString().trim());

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }

        if (Build.VERSION.SDK_INT < 29) {
            startLegacyMicTranslation();
            return;
        }

        MediaProjectionManager pm =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void startLegacyMicTranslation() {
        Intent i = new Intent(this, AudioCaptureService.class);
        i.putExtra("legacyMic", true);
        i.putExtra("lang", langCode());
        i.putExtra("quality", String.valueOf(quality.getSelectedItem()));
        i.putExtra("spatial", String.valueOf(spatial.getSelectedItem()));
        i.putExtra("server", server.getText().toString().trim());
        i.putExtra("voiceMode", voiceMode.getSelectedItemPosition() == 1 ? "clone" : "fast");

        startForegroundService(i);
        status.setText(
                "Modo compatible Fire OS 7 / Android 9: captura por microfono con cancelacion de eco. "
                + "La voz original se atenua, pero no puede eliminarse al 100% sin captura interna."
        );
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestCapture();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || data == null) {
            status.setText("Permiso de captura cancelado.");
            return;
        }

        Intent i = new Intent(this, AudioCaptureService.class);
        i.putExtra("resultCode", resultCode);
        i.putExtra("resultData", data);
        i.putExtra("lang", langCode());
        i.putExtra("quality", String.valueOf(quality.getSelectedItem()));
        i.putExtra("spatial", String.valueOf(spatial.getSelectedItem()));
        i.putExtra("server", server.getText().toString().trim());
        i.putExtra("voiceMode", voiceMode.getSelectedItemPosition() == 1 ? "clone" : "fast");

        startForegroundService(i);
        status.setText((voiceMode.getSelectedItemPosition() == 1 ? "Clonado multi-hablante continuo" : "Rapido continuo") + ". Ahora abre una aplicacion desde la lista. "
                + "Si bloquea AudioPlaybackCapture, INIT no recibira su audio.");
    }

    private String langCode() {
        String s = String.valueOf(lang.getSelectedItem());
        int a = s.indexOf('(');
        int b = s.indexOf(')');
        return a >= 0 && b > a ? s.substring(a + 1, b) : "es-ES";
    }

    private String load(String k, String d) {
        return getSharedPreferences("init", MODE_PRIVATE).getString(k, d);
    }

    private void save(String k, String v) {
        getSharedPreferences("init", MODE_PRIVATE).edit().putString(k, v).apply();
    }
}
