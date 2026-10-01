package com.amap.adblocker;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.XposedBridge;

public class XLog {
    public static final String TAG = "AmapAdBlock";
    private static boolean enabled = true;
    public static File file;

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static void i(String s) {
        if (!enabled) return;
        if (s == null) s = "";
        int n = s.length();
        if (n <= 3000) {
            emit(s);
            return;
        }
        int pos = 0;
        int part = 0;
        while (pos < n) {
            int end = Math.min(n, pos + 3000);
            emit("(" + (++part) + ") " + s.substring(pos, end));
            pos = end;
        }
    }

    /** true 时只写文件，不打 logcat（用于大数据量 dump） */
    public static boolean silent = false;

    private static void emit(String s) {
        if (!silent) {
            try {
                XposedBridge.log(TAG + ": " + s);
            } catch (Throwable t) {
                android.util.Log.i(TAG, s);
            }
        }
        if (file != null) {
            try {
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write((s + "\n").getBytes("UTF-8"));
                fos.close();
            } catch (Throwable t) {
                file = null;
            }
        }
    }

    public static void resetFile() {
        try {
            if (file != null) {
                new FileOutputStream(file, false).close();
            }
        } catch (Throwable t) {
        }
    }

    public static void d(String s) {
        i(s);
    }

    public static void e(String s) {
        try {
            XposedBridge.log(TAG + " [E]: " + s);
        } catch (Throwable t) {
            android.util.Log.e(TAG, s);
        }
    }
}
