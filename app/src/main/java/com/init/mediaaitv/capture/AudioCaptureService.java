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
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.init.mediaaitv.MainActivity;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class AudioCaptureService extends Service {
    public static final String ACTION_STOP = "com.init.mediaaitv.STOP";
    public static volatile boolean running = false;
    public static volatile String lastStatus = "Traduccion detenida.";
    public static volatile String lastDetectedSourceLanguage = "";

    private static final String TAG = "InitCapture";
    private static final String CHANNEL = "init_translate";

    private MediaProjection projection;
    private AudioRecord recorder;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private PcmPlayer player;
    private LocalTtsPlayer localTts;
    private ExecutorService executor;
    private final BlockingQueue<byte[]> uploadQueue = new LinkedBlockingQueue<>();

    private volatile boolean stop;
    private volatile boolean shuttingDown;
    private volatile String lastNotice = "";

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int originalMusicVolume = -1;
    private boolean sourceMuted = false;
    private boolean legacyMicMode = false;
    private TranslationClient client;
    private String voiceMode = "fast";
    private long captureStartedAtMs = 0L;
    private long lastLegacyNoSignalNoticeMs = 0L;
    private boolean legacySignalConfirmed = false;
    private boolean backendConfirmed = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(41, notification("Preparing continuous translation..."));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            lastStatus = "Traduccion detenida por el usuario.";
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
        voiceMode = "clone".equals(intent.getStringExtra("voiceMode")) ? "clone" : "fast";
        legacyMicMode = intent.getBooleanExtra("legacyMic", false) || Build.VERSION.SDK_INT < 29;

        if (!legacyMicMode && (resultCode != Activity.RESULT_OK || resultData == null)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int min = AudioRecord.getMinBufferSize(
                    48000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (legacyMicMode) {
                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(min * 4, 192000))
                        .build();

                // Fire OS 7 / Android 9 cannot use AudioPlaybackCapture. In this fallback
                // we intentionally leave AEC/NS OFF: the TV loudspeaker is the source we
                // need to transcribe, so echo cancellation can erase the program audio.
            } else {
                MediaProjectionManager pm =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = pm.getMediaProjection(resultCode, resultData);
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        shutdown();
                        stopSelf();
                    }
                }, new android.os.Handler(getMainLooper()));

                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();

                recorder = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(min * 4, 192000))
                        .setAudioPlaybackCaptureConfig(config)
                        .build();
            }

            player = new PcmPlayer();
            if ("fast".equals(voiceMode)) {
                localTts = new LocalTtsPlayer(this, lang == null ? "es-ES" : lang);
            }

            client = new TranslationClient(
                    server,
                    lang == null ? "es-ES" : lang,
                    quality == null ? "Auto AI" : quality,
                    spatial == null ? "Spatial AI automatic" : spatial,
                    voiceMode
            );

            executor = Executors.newFixedThreadPool(3);
            stop = false;
            shuttingDown = false;
            running = true;

            notice(
                    (legacyMicMode ? "Fire OS legacy mic · " : "")
                            + ("fast".equals(voiceMode) ? "Rapido continuo" : "Voces originales multi-hablante")
                            + " · destino " + (lang == null ? "es-ES" : lang)
            );

            recorder.startRecording();
            captureStartedAtMs = System.currentTimeMillis();
            legacySignalConfirmed = false;
            backendConfirmed = false;
            lastLegacyNoSignalNoticeMs = 0L;
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
            }
            executor.execute(this::captureLoop);
            executor.execute(this::uploadLoop);
            executor.execute(this::pollLoop);

        } catch (Throwable t) {
            Log.e(TAG, "Cannot start capture", t);
            lastStatus = "No se pudo iniciar captura: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            updateNotification(lastStatus);
            shutdown();
            stopSelf();
        }

        return START_STICKY;
    }

    private void captureLoop() {
        byte[] buf = new byte[96000];
        int pos = 0;

        while (!stop && recorder != null) {
            int n = recorder.read(buf, pos, buf.length - pos, AudioRecord.READ_BLOCKING);
            if (n <= 0) continue;

            pos += n;
            if (pos >= buf.length) {
                if (legacyMicMode) updateLegacySignalDiagnostic(buf, pos);
                uploadQueue.offer(Arrays.copyOf(buf, pos));
                pos = 0;
            }
        }
    }

    private void updateLegacySignalDiagnostic(byte[] pcm, int length) {
        int peak = 0;
        int usable = Math.min(length, pcm.length) & ~1;
        for (int i = 0; i < usable; i += 2) {
            int sample = (short)((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > peak) peak = abs;
        }

        long now = System.currentTimeMillis();
        if (peak >= 700) {
            if (!legacySignalConfirmed) {
                legacySignalConfirmed = true;
                notice("Fire OS 7: microfono activo y audio detectado.");
            }
            return;
        }

        if (!legacySignalConfirmed
                && now - captureStartedAtMs >= 5000L
                && now - lastLegacyNoSignalNoticeMs >= 5000L) {
            lastLegacyNoSignalNoticeMs = now;
            notice("Fire OS 7: microfono abierto pero sin audio util. Sube el volumen de la TV o revisa la entrada de microfono.");
        }
    }

    private void uploadLoop() {
        while (!stop) {
            try {
                byte[] chunk = uploadQueue.poll(500, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;

                boolean ok = client != null && client.pushPcm(chunk);
                if (ok && !backendConfirmed) {
                    backendConfirmed = true;
                    notice("Backend conectado: audio recibido. Detectando idioma...");
                }
                if (!ok && client != null && !client.getLastError().isEmpty()) {
                    notice("Servidor IA: " + client.getLastError());
                    uploadQueue.offer(chunk);
                    Thread.sleep(800);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Upload loop error", t);
            }
        }
    }

    private void pollLoop() {
        while (!stop) {
            try {
                if (client == null) {
                    Thread.sleep(200);
                    continue;
                }

                if ("fast".equals(voiceMode)) {
                    String text = client.pollText();
                    if (!client.getLastSourceLanguage().isEmpty()) {
                        lastDetectedSourceLanguage = client.getLastSourceLanguage();
                    }
                    if ("same-language-skip".equals(client.getLastMode())) {
                        notice("Idioma detectado: " + client.getLastSourceLanguage()
                                + " · coincide con el destino. No se dobla.");
                        Thread.sleep(150);
                        continue;
                    }
                    if (!text.isEmpty()) {
                        enableSourceReplacement();
                        boolean localOk = localTts != null && localTts.speak(text);
                        if (!localOk) {
                            byte[] fallback = client.synthesizeText(text);
                            if (fallback.length > 0 && player != null) player.write(fallback);
                        }
                        notice(
                                (legacyMicMode ? "Fire legacy · " : "")
                                        + "Rápido continuo · retraso cola: "
                                        + client.getLastQueuedSeconds() + " s"
                        );
                    } else {
                        Thread.sleep(150);
                    }
                } else {
                    byte[] translated = client.pollPcm();
                    if (!client.getLastSourceLanguage().isEmpty()) {
                        lastDetectedSourceLanguage = client.getLastSourceLanguage();
                    }

                    if ("same-language-skip".equals(client.getLastMode())) {
                        notice("Idioma detectado: " + client.getLastSourceLanguage()
                                + " · coincide con el destino. No se dobla.");
                        Thread.sleep(150);
                        continue;
                    }

                    if (
                            "translated-cloned-speaker".equals(client.getLastMode())
                            || "translated-fallback-voice".equals(client.getLastMode())
                    ) {
                        enableSourceReplacement();
                    }

                    if (translated.length > 0 && !stop) {
                        notice(
                                (legacyMicMode ? "Fire legacy · " : "")
                                        + "Multi-hablante · retraso cola: "
                                        + client.getLastQueuedSeconds() + " s"
                        );
                        requestDuck();
                        if (player != null) player.write(translated);
                    } else {
                        Thread.sleep(200);
                    }
                }

                if (!client.getLastError().isEmpty()) {
                    notice("Backend IA: " + client.getLastError());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Poll loop error", t);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void notice(String text) {
        if (text == null || text.equals(lastNotice)) return;
        lastNotice = text;
        lastStatus = text;
        updateNotification(text);
    }

    private void enableSourceReplacement() {
        if (sourceMuted) return;

        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }

        try {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

            if (legacyMicMode) {
                // Fire OS 7 has no internal playback capture. The microphone needs
                // to keep hearing the source, so only attenuate it. AEC/NS reduces
                // the translated voice feeding back into the microphone.
                // Keep enough source level for the microphone fallback to continue hearing
                // subsequent dialogue. Too much ducking starves ASR after the first line.
                int reduced = Math.max(1, Math.round(originalMusicVolume * 0.60f));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, reduced, 0);
                sourceMuted = true;
                notice("Fire OS 7: voz original atenuada; doblaje IA prioritario.");
            } else {
                try {
                    audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_MUTE,
                            0
                    );
                } catch (Throwable ignored) {
                }
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        0,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                );
                sourceMuted = true;
                notice("Audio original silenciado; doblaje IA activo.");
            }

        } catch (Throwable t) {
            Log.w(TAG, "Could not change source media volume", t);
        }
    }

    private void restoreSourceAudio() {
        if (!sourceMuted || audioManager == null || originalMusicVolume < 0) return;

        try {
            if (!legacyMicMode) {
                try {
                    audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_UNMUTE,
                            0
                    );
                } catch (Throwable ignored) {
                }
            }

            audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    originalMusicVolume,
                    0
            );
        } catch (Throwable t) {
            Log.w(TAG, "Could not restore original media volume", t);
        } finally {
            sourceMuted = false;
            originalMusicVolume = -1;
        }
    }

    private void requestDuck() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest =
                        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(
                                        new AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                .build()
                                )
                                .setAcceptsDelayedFocusGain(false)
                                .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        }
    }

    private synchronized void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;

        stop = true;
        running = false;
        lastDetectedSourceLanguage = "";

        try { if (client != null) client.stopSession(); } catch (Throwable ignored) {}
        try { if (recorder != null) recorder.stop(); } catch (Throwable ignored) {}

        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) {}
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) {}
            noiseSuppressor = null;
        }

        if (recorder != null) recorder.release();
        recorder = null;

        if (localTts != null) localTts.close();
        localTts = null;

        if (player != null) player.close();
        player = null;

        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) {}
        }

        if (executor != null) executor.shutdownNow();
        executor = null;

        uploadQueue.clear();
        restoreSourceAudio();

        if (audioManager != null && focusRequest != null && Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL,
                        "INIT live translation",
                        NotificationManager.IMPORTANCE_LOW
                )
        );
    }

    private Notification notification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(
                this,
                0,
                i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

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
