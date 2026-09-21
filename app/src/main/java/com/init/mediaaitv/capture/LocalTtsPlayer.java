package com.init.mediaaitv.capture;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LocalTtsPlayer implements TextToSpeech.OnInitListener {
    private static final String TAG = "InitLocalTTS";

    private final TextToSpeech tts;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready = false;
    private volatile boolean supported = false;
    private final Locale locale;

    public LocalTtsPlayer(Context context, String languageTag) {
        locale = Locale.forLanguageTag(languageTag == null ? "es-ES" : languageTag);
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                );
                int result = tts.setLanguage(locale);
                supported = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(1.08f);
                ready = true;
            } catch (Throwable t) {
                Log.w(TAG, "TTS init failed", t);
            }
        }
        readyLatch.countDown();
    }

    public boolean awaitReady() {
        if (ready) return supported;
        try {
            readyLatch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ready && supported;
    }

    public boolean speak(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        if (!awaitReady()) return false;
        int result = tts.speak(
                text,
                TextToSpeech.QUEUE_ADD,
                null,
                "init-" + UUID.randomUUID()
        );
        return result == TextToSpeech.SUCCESS;
    }

    public void stop() {
        try { tts.stop(); } catch (Throwable ignored) {}
    }

    public void close() {
        try { tts.stop(); } catch (Throwable ignored) {}
        try { tts.shutdown(); } catch (Throwable ignored) {}
    }
}
