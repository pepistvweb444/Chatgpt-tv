package com.init.mediaaitv.remote;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.init.mediaaitv.MainActivity;
import com.init.mediaaitv.capture.AudioCaptureService;

public final class RemoteShortcutService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_CAPTIONS || k == KeyEvent.KEYCODE_PROG_RED) {
            if (AudioCaptureService.running) {
                Intent stop = new Intent(this, AudioCaptureService.class).setAction(AudioCaptureService.ACTION_STOP);
                startService(stop);
            } else {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("shortcutStart", true);
                startActivity(i);
            }
            return true;
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
