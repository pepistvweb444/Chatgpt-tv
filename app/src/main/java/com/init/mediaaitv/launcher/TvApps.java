package com.init.mediaaitv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class TvApps {
    public static final class Item {
        public final String label;
        public final String pkg;
        public Item(String label, String pkg) { this.label = label; this.pkg = pkg; }
        @Override public String toString() { return label; }
    }

    public static List<Item> list(Context c) {
        PackageManager pm = c.getPackageManager();
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(q, PackageManager.MATCH_ALL)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                result.put(pkg, new Item(String.valueOf(r.loadLabel(pm)), pkg));
            }
        }
        ArrayList<Item> out = new ArrayList<>(result.values());
        out.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    public static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }
}
