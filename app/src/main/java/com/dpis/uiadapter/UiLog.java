package com.dpis.uiadapter;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/** Single log prefix so `adb logcat | grep DPIS_UI` shows every milestone. */
public final class UiLog {

    public static final String TAG = "DPIS_UI";

    private UiLog() {
    }

    public static void i(String message) {
        Log.i(TAG, message);
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }

    public static void w(String message) {
        Log.w(TAG, message);
        try {
            XposedBridge.log(TAG + ": WARN " + message);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        try {
            XposedBridge.log(TAG + ": ERROR " + message + "\n" + Log.getStackTraceString(throwable));
        } catch (Throwable ignored) {
        }
    }
}
