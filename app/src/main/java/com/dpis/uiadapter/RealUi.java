package com.dpis.uiadapter;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * The host display's <b>real</b> metrics, captured before DPIS rewrites them.
 *
 * <p>DPIS's whole job is to make the host believe it runs on a different display: it
 * overrides the app's {@code Configuration.densityDpi} / {@code DisplayMetrics} and
 * hooks {@code Display}. Any view this module builds with the activity's resources
 * therefore inherits that scale — the floating ball and the panel grew and shrank with
 * the very value they were meant to control.</p>
 *
 * <p>{@link #capture()} runs from {@code initZygote}, which LSPatch calls before the
 * DPIS module is even instantiated, so {@code Resources.getSystem().getDisplayMetrics()}
 * still describes the physical display. The values are copied into plain fields, so a
 * later mutation of that shared metrics object cannot move them.</p>
 */
public final class RealUi {

    private static volatile float density;
    private static volatile float scaledDensity;
    private static volatile int width;
    private static volatile int height;

    private RealUi() {
    }

    /** Idempotent; incomplete captures are retried on the next call. */
    public static void capture() {
        if (complete()) return;
        synchronized (RealUi.class) {
            if (complete()) return;
            try {
                DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
                if (metrics == null) return;
                if (density <= 0f && metrics.density > 0f) {
                    density = metrics.density;
                }
                if (scaledDensity <= 0f) {
                    scaledDensity = metrics.scaledDensity > 0f
                            ? metrics.scaledDensity : metrics.density;
                }
                if (width <= 0) width = metrics.widthPixels;
                if (height <= 0) height = metrics.heightPixels;
                if (complete()) {
                    UiLog.i("real display: " + width + "x" + height
                            + " density=" + density + " scaledDensity=" + scaledDensity);
                }
            } catch (Throwable throwable) {
                UiLog.w("real display capture failed: "
                        + throwable.getClass().getSimpleName());
            }
        }
    }

    private static boolean complete() {
        return density > 0f && width > 0 && height > 0;
    }

    public static float density() {
        capture();
        return density > 0f ? density : 0f;
    }

    public static float scaledDensity() {
        capture();
        return scaledDensity > 0f ? scaledDensity : density();
    }

    public static int width() {
        capture();
        return width;
    }

    public static int height() {
        capture();
        return height;
    }

    /** For diagnostics: what the given context reports, which is DPIS-scaled when unhooked. */
    public static String describe(Context context) {
        if (context == null) return "null";
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.widthPixels + "x" + metrics.heightPixels
                    + " density=" + metrics.density;
        } catch (Throwable throwable) {
            return "unreadable";
        }
    }
}
