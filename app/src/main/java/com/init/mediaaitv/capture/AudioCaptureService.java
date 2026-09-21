package com.init.mediaaitv.capture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.init.mediaaitv.MainActivity;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioCaptureService extends Service {
    public static final String ACTION_STOP = "com.init.mediaaitv.STOP";
    public static volatile boolean running = false;
    private static final String TAG = "InitCapture";
    private static final String CHANNEL = "init_translate";

    private MediaProjection projection;
    private AudioRecord recorder;
    private PcmPlayer player;
    private ExecutorService executor;
    private volatile boolean stop;
    private volatile String lastNotice = "";
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(41, notification("Preparing translation..."));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra("resultData");
        String lang = intent.getStringExtra("lang");
        String quality = intent.getStringExtra("quality");
        String spatial = intent.getStringExtra("spatial");
        String server = intent.getStringExtra("server");
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = pm.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    shutdown();
                    stopSelf();
                }
            }, new android.os.Handler(getMainLooper()));

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min * 4, 96000))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            player = new PcmPlayer();
            TranslationClient client = new TranslationClient(
                    server,
                    lang == null ? "es-ES" : lang,
                    quality == null ? "Auto AI" : quality,
                    spatial == null ? "Spatial AI automatic" : spatial
            );

            executor = Executors.newFixedThreadPool(2);
            stop = false;
            running = true;
            updateNotification("Translation ON - " + (lang == null ? "es-ES" : lang));
            recorder.startRecording();
            executor.execute(() -> captureLoop(client));
        } catch (Throwable t) {
            Log.e(TAG, "Cannot start playback capture", t);
            shutdown();
            stopSelf();
        }
        return START_STICKY;
    }

    private void captureLoop(TranslationClient client) {
        byte[] buf = new byte[96000];
        int pos = 0;
        while (!stop && recorder != null) {
            int n = recorder.read(buf, pos, buf.length - pos, AudioRecord.READ_BLOCKING);
            if (n <= 0) continue;
            pos += n;
            if (pos >= buf.length) {
                byte[] chunk = Arrays.copyOf(buf, pos);
                pos = 0;
                if (!requestInFlight.compareAndSet(false, true)) {
                    notice("Procesando traduccion; descartando audio atrasado para evitar cola.");
                    continue;
                }

                executor.execute(() -> {
                    try {
                        if (!hasSignal(chunk)) {
                            notice("Sin audio capturable. La app fuente puede bloquear la captura.");
                            return;
                        }

                        byte[] translated = client.translatePcm(chunk);
                        if (!client.getLastError().isEmpty()) {
                            notice("Backend IA: " + client.getLastError());
                            return;
                        }

                        if ("pcm-loopback".equals(client.getLastMode())) {
                            notice("Modo prueba: el servidor devuelve el audio original, sin traducir.");
                        } else if ("buffering".equals(client.getLastMode())) {
                            notice("Acumulando voz para traducir...");
                        } else if (translated.length > 0) {
                            notice("Traduccion IA activa - " + client.getLastMode());
                        }

                        if (translated.length > 0 && !stop) {
                            requestDuck();
                            if (player != null) player.write(translated);
                        }
                    } finally {
                        requestInFlight.set(false);
                    }
                });
            }
        }
    }

    private boolean hasSignal(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short)((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int a = Math.abs(sample);
            if (a > peak) peak = a;
            if (peak > 300) return true;
        }
        return false;
    }

    private void notice(String text) {
        if (text == null || text.equals(lastNotice)) return;
        lastNotice = text;
        updateNotification(text);
    }

    private void requestDuck() {
        if (audioManager == null) audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAcceptsDelayedFocusGain(false)
                        .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        }
    }

    private void shutdown() {
        stop = true;
        running = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        if (recorder != null) recorder.release();
        recorder = null;
        if (player != null) player.close();
        player = null;
        if (projection != null) projection.stop();
        projection = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
        if (audioManager != null && focusRequest != null && Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "INIT live translation", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("INIT Media AI TV")
                .setContentText(text)
                .setContentIntent(p)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(41, notification(text));
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
