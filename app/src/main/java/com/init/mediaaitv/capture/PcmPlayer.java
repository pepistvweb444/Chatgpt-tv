package com.init.mediaaitv.capture;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

public final class PcmPlayer {
    private final AudioTrack track;

    public PcmPlayer() {
        int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 2, 48000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
    }

    public void write(byte[] pcm) {
        if (pcm != null && pcm.length > 0) track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
    }

    public void close() {
        try { track.stop(); } catch (Exception ignored) {}
        track.release();
    }
}
