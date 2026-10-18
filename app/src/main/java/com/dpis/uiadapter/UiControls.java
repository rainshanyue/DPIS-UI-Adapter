package com.dpis.uiadapter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Controls that only ever use explicit pixel sizes.
 *
 * <p>DPIS rewrites the density of <b>every</b> {@code Resources} created in the hooked
 * process — it hooks {@code ResourcesImpl.updateConfiguration} globally and re-applies its
 * viewport override, so even a context built with {@code createConfigurationContext} comes
 * back scaled (measured on device: a 600 dpi request reported 8.325 density at 45 %).</p>
 *
 * <p>That leaves platform widgets unusable for an overlay that must not scale: a
 * {@code Switch}'s thumb, a {@code SeekBar}'s track and a {@code RadioButton}'s button are
 * all sized in dp by their own theme. These replacements draw with a {@code Paint} at
 * dimensions handed in as pixels, so nothing inside them can follow the host's scale.</p>
 */
final class UiControls {

    private UiControls() {
    }

    /** A two-state switch: rounded track plus a sliding thumb, sized in pixels. */
    static final class Toggle extends View {

        interface OnChanged {
            void onChanged(Toggle toggle, boolean checked);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int widthPx;
        private final int heightPx;
        private final int onColor;
        private final int offColor;
        private final int thumbColor;

        private boolean checked;
        private OnChanged listener;

        Toggle(Context context, int widthPx, int heightPx,
               int onColor, int offColor, int thumbColor) {
            super(context);
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.onColor = onColor;
            this.offColor = offColor;
            this.thumbColor = thumbColor;
            setClickable(true);
            setFocusable(true);
            setContentDescription("开关");
        }

        void setChecked(boolean value) {
            if (checked == value) return;
            checked = value;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        void setOnChanged(OnChanged changed) {
            listener = changed;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(widthPx, heightPx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = heightPx / 2f;
            paint.setColor(checked ? onColor : offColor);
            canvas.drawRoundRect(new RectF(0f, 0f, widthPx, heightPx),
                    radius, radius, paint);
            float inset = Math.max(2f, heightPx * 0.12f);
            float thumbRadius = radius - inset;
            float centerX = checked ? widthPx - radius : radius;
            paint.setColor(thumbColor);
            canvas.drawCircle(centerX, radius, thumbRadius, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_UP:
                    checked = !checked;
                    invalidate();
                    performClick();
                    if (listener != null) listener.onChanged(this, checked);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    /** A horizontal value slider drawn with explicit pixels; drag sets the value. */
    static final class Slider extends View {

        interface OnChanged {
            void onChanged(Slider slider, int value, boolean fromUser);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int min;
        private final int max;
        private final int heightPx;
        private final int trackThicknessPx;
        private final int thumbRadiusPx;
        private final int activeColor;
        private final int inactiveColor;
        private final int thumbColor;

        private int value;
        private OnChanged listener;

        Slider(Context context, int min, int max, int value, int heightPx,
               int trackThicknessPx, int thumbRadiusPx,
               int activeColor, int inactiveColor, int thumbColor) {
            super(context);
            this.min = min;
            this.max = Math.max(min + 1, max);
            this.heightPx = heightPx;
            this.trackThicknessPx = trackThicknessPx;
            this.thumbRadiusPx = thumbRadiusPx;
            this.activeColor = activeColor;
            this.inactiveColor = inactiveColor;
            this.thumbColor = thumbColor;
            this.value = Math.max(min, Math.min(this.max, value));
            setFocusable(true);
        }

        int getProgress() {
            return value;
        }

        void setProgress(int progress) {
            int clamped = Math.max(min, Math.min(max, progress));
            if (value == clamped) return;
            value = clamped;
            invalidate();
        }

        void setOnChanged(OnChanged changed) {
            listener = changed;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width <= 0) width = thumbRadiusPx * 4;
            setMeasuredDimension(width, heightPx);
        }

        private float trackLeft() {
            return thumbRadiusPx + 2f;
        }

        private float trackRight() {
            return Math.max(trackLeft() + 1f, getWidth() - thumbRadiusPx - 2f);
        }

        private float xForValue(int v) {
            float fraction = (v - min) / (float) (max - min);
            return trackLeft() + fraction * (trackRight() - trackLeft());
        }

        private int valueForX(float x) {
            float span = trackRight() - trackLeft();
            if (span <= 0f) return min;
            float fraction = (x - trackLeft()) / span;
            fraction = Math.max(0f, Math.min(1f, fraction));
            return min + Math.round(fraction * (max - min));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float centerY = heightPx / 2f;
            float left = trackLeft();
            float right = trackRight();
            float thumbX = xForValue(value);
            float half = trackThicknessPx / 2f;
            paint.setColor(inactiveColor);
            canvas.drawRoundRect(new RectF(left, centerY - half, right, centerY + half),
                    half, half, paint);
            paint.setColor(activeColor);
            canvas.drawRoundRect(new RectF(left, centerY - half, thumbX, centerY + half),
                    half, half, paint);
            paint.setColor(thumbColor);
            canvas.drawCircle(thumbX, centerY, thumbRadiusPx, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // The panel lives in a ScrollView; without this the vertical scroll
                    // gesture steals the horizontal drag.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    update(event.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    update(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    update(event.getX());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void update(float x) {
            int next = valueForX(x);
            if (next == value) {
                invalidate();
                return;
            }
            value = next;
            invalidate();
            if (listener != null) listener.onChanged(this, value, true);
        }
    }

}
