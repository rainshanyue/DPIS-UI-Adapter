package com.dpis.uiadapter;

/** Small, Android-free geometry helpers for keeping the ball on screen. */
public final class Geometry {

    private Geometry() {
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamps a top-left position for a view of {@code viewW x viewH} inside an area.
     *
     * @param topInset    area pixels that are covered by the status bar / cutout
     * @param bottomInset area pixels that are covered by the navigation bar
     */
    public static int[] clampPosition(int x, int y, int viewW, int viewH,
                                      int areaW, int areaH, int topInset, int bottomInset) {
        int minX = 0;
        int minY = Math.max(0, topInset);
        int maxX = Math.max(minX, areaW - Math.max(0, viewW));
        int maxY = Math.max(minY, areaH - Math.max(0, bottomInset) - Math.max(0, viewH));
        return new int[]{clamp(x, minX, maxX), clamp(y, minY, maxY)};
    }

    /**
     * Horizontal position as a fraction of the travel range ({@code areaW - viewW}).
     * Taken from the spot the user settled on: on the 1440x3200 test device a 203px ball
     * sat at x=1215, i.e. 1215/1237 = 0.9822 of the range — a little away from the edge.
     */
    private static final float DEFAULT_X_FRACTION = 0.982214f;

    /**
     * Vertical position of the ball's <b>centre</b>, as a fraction of the full drawing
     * height. Deliberately not the "usable" height: window insets are still zero on the
     * first resume (verified on device — the same build landed 96px lower right after a
     * data wipe than after a long-running session). Same measurement: centre 1316 of a
     * 3039px area → 0.433036.
     */
    private static final float DEFAULT_Y_FRACTION = 0.433036f;

    /**
     * The position a fresh install (or a cleared {@code ui.<host>.x/y}) starts from.
     * Expressed as fractions of the screen so another resolution keeps the same relative
     * spot instead of the same pixels.
     */
    public static int[] defaultPosition(int viewW, int viewH,
                                        int areaW, int areaH,
                                        int topInset, int bottomInset) {
        int w = Math.max(0, viewW);
        int h = Math.max(0, viewH);
        int x = Math.round((areaW - w) * DEFAULT_X_FRACTION);
        int y = Math.round(areaH * DEFAULT_Y_FRACTION) - h / 2;
        return clampPosition(x, y, w, h, areaW, areaH, topInset, bottomInset);
    }
}
