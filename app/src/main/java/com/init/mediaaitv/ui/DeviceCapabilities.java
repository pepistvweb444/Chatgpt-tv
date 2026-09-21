package com.init.mediaaitv.ui;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.view.Display;
import android.view.WindowManager;

public final class DeviceCapabilities {
    private static boolean decoder(String mime) {
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (i.isEncoder()) continue;
            for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    public static String summary(Context c) {
        WindowManager w = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        Display d = w.getDefaultDisplay();
        Display.Mode m = d.getMode();
        boolean av1 = decoder("video/av01");
        boolean hevc = decoder("video/hevc");
        boolean uhd8 = m.getPhysicalWidth() >= 7000 || m.getPhysicalHeight() >= 4000;
        return "Pantalla " + m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + " @ " + Math.round(m.getRefreshRate()) + " Hz"
                + " - AV1 " + (av1 ? "OK" : "--")
                + " - HEVC " + (hevc ? "OK" : "--")
                + " - salida 8K " + (uhd8 ? "OK" : "escalado/servidor");
    }
}
