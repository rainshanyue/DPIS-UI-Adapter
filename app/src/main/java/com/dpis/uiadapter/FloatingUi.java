package com.dpis.uiadapter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Host-process floating UI. No system-overlay permission is used: the ball is a
 * TYPE_APPLICATION window added through the current Activity's WindowManager (with a
 * decor-view child fallback), and the panel is an in-app PopupWindow.
 */
public final class FloatingUi {

    private static final String ACTION_PREFIX = "com.dpis.uiadapter.action.";
    public static final String ACTION_SHOW = ACTION_PREFIX + "SHOW";
    public static final String ACTION_HIDE = ACTION_PREFIX + "HIDE";
    public static final String ACTION_TOGGLE = ACTION_PREFIX + "TOGGLE";
    public static final String ACTION_RESET = ACTION_PREFIX + "RESET";
    public static final String ACTION_CONFIG = ACTION_PREFIX + "CONFIG";

    private static final String EXTRA_TOKEN = "token";
    private static final String TOKEN = "dpis-ui-adapter";
    private static final String EXTRA_SCALE = "scale_percent";
    private static final String EXTRA_WIDTH = "width_dp";
    private static final String EXTRA_FONT = "font_scale_percent";
    private static final String EXTRA_MODE = "viewport_mode";
    private static final String EXTRA_DPIS_ENABLED = "dpis_enabled";
    private static final String EXTRA_LOG_ENABLED = "log_enabled";

    private static final int REQ_EXPORT = 0x44504953;
    private static final int REQ_RESTART = 0x44504954;
    private static final int BALL_DP = 54;
    private static final int PANEL_WIDTH_DP = 320;

    private static boolean hooksInstalled;
    private static String hostPackage;

    private static Activity currentActivity;
    private static Activity ballActivity;
    private static Activity lastResumeActivity;
    private static long lastResumeAt;
    private static TextView ballView;
    private static WindowManager windowManager;
    private static WindowManager.LayoutParams ballParams;
    private static ViewGroup decorParent;
    private static FrameLayout.LayoutParams ballFrameParams;
    private static boolean windowAttached;
    private static boolean decorAttached;
    private static int decorOffsetX;
    private static int decorOffsetY;
    private static View layoutDecor;
    private static View.OnLayoutChangeListener layoutListener;

    private static PopupWindow panelPopup;
    /** The panel currently on screen, so it can be rebuilt in place after a reset. */
    private static PanelHolder panelHolder;
    /** True while an overlay (panel or confirm) is up and the ball is hidden behind it. */
    private static boolean ballHiddenForOverlay;
    private static View panelInDecor;

    private static Handler handler;
    private static final int GESTURE_PRESS_COUNT = 3;
    private static final long GESTURE_WINDOW_MS = 1200L;

    private static Runnable longPressRunnable;
    private static PopupWindow confirmPopup;
    private static int volumeDownCount;
    private static long lastVolumeDownAt;
    private static boolean dragging;
    private static boolean longPressFired;
    private static float downRawX;
    private static float downRawY;
    private static int downBallX;
    private static int downBallY;

    private static boolean receiverRegistered;
    private static BroadcastReceiver receiver;
    private static Context appContext;
    private static String pendingExportJson;
    private static String pendingExportPath;

    private static AdapterConfig config;

    private FloatingUi() {
    }

    // ------------------------------------------------------------------
    // Xposed lifecycle hooks
    // ------------------------------------------------------------------

    public static void installActivityHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (hooksInstalled || lpparam == null) return;
        synchronized (FloatingUi.class) {
            if (hooksInstalled) return;
            hooksInstalled = true;
            hostPackage = lpparam.packageName;
            HostEnv.setFromLoadPackage(lpparam);
            handler = new Handler(Looper.getMainLooper());
            longPressRunnable = () -> {
                longPressFired = true;
                if (ballView != null) {
                    ballView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                showHideConfirm();
            };
            try {
                ClassLoader loader = lpparam.classLoader != null
                        ? lpparam.classLoader : Activity.class.getClassLoader();
                XposedHelpers.findAndHookMethod("android.app.Activity", loader, "onResume",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityResumed((Activity) param.thisObject);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onResume UI hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Activity", loader, "onPause",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityPaused((Activity) param.thisObject);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onPause UI hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Activity", loader, "onStop",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityStopped((Activity) param.thisObject);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onStop UI hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Activity", loader, "onDestroy",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityDestroyed((Activity) param.thisObject);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onDestroy UI hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                        "onConfigurationChanged", Configuration.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityConfigurationChanged((Activity) param.thisObject);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onConfigurationChanged UI hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                        "onActivityResult", int.class, int.class, Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.thisObject instanceof Activity) {
                                        onActivityResult((Activity) param.thisObject,
                                                (Integer) param.args[0], (Integer) param.args[1],
                                                (Intent) param.args[2]);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("onActivityResult UI hook failed", throwable);
                                }
                            }
                        });
                // Some host activities override onResume/onPause without calling super.
                // Instrumentation owns the dispatch, so hooking it makes the overlay
                // independent of that host implementation detail.
                XposedHelpers.findAndHookMethod("android.app.Instrumentation", loader,
                        "callActivityOnResume", Activity.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Activity) {
                                        onActivityResumed((Activity) param.args[0]);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("Instrumentation resume hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Instrumentation", loader,
                        "callActivityOnPause", Activity.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Activity) {
                                        onActivityPaused((Activity) param.args[0]);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("Instrumentation pause hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Instrumentation", loader,
                        "callActivityOnStop", Activity.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Activity) {
                                        onActivityStopped((Activity) param.args[0]);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("Instrumentation stop hook failed", throwable);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.app.Instrumentation", loader,
                        "callActivityOnDestroy", Activity.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Activity) {
                                        onActivityDestroyed((Activity) param.args[0]);
                                    }
                                } catch (Throwable throwable) {
                                    UiLog.e("Instrumentation destroy hook failed", throwable);
                                }
                            }
                        });
                // Volume-down x3 is the in-app way back when the ball is hidden, so the
                // gesture has to survive every activity the host shows.
                try {
                    XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                            "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        if (param.args != null && param.args.length > 0
                                                && param.args[0] instanceof KeyEvent) {
                                            onHostKeyEvent((KeyEvent) param.args[0]);
                                        }
                                    } catch (Throwable throwable) {
                                        UiLog.e("volume gesture hook failed", throwable);
                                    }
                                }
                            });
                } catch (Throwable throwable) {
                    UiLog.w("volume gesture unavailable: "
                            + throwable.getClass().getSimpleName());
                }
                UiLog.i("activity hooks installed: host=" + hostPackage);
            } catch (Throwable throwable) {
                UiLog.e("activity hook installation failed", throwable);
            }
        }
    }

    private static void onActivityResumed(Activity activity) {
        if (activity == null) return;
        long now = SystemClock.uptimeMillis();
        if (activity == lastResumeActivity && now - lastResumeAt < 300L) return;
        lastResumeActivity = activity;
        lastResumeAt = now;
        String pkg = activity.getPackageName();
        if (hostPackage != null && !hostPackage.equals(pkg)) return;
        if (hostPackage == null) hostPackage = pkg;
        HostEnv.updateFromActivity(activity);
        currentActivity = activity;
        ensureReceiver(activity.getApplicationContext());
        AdapterConfig loaded = loadConfig(activity);
        AdapterConfig.PackageConfig pkgConfig = loaded.getPackage(hostPackage);
        if (pkgConfig.enabled) {
            attachBall(activity);
        } else {
            detachBall();
        }
        UiLog.i("activity resumed: " + activity.getClass().getName()
                + ", ballEnabled=" + pkgConfig.enabled);
    }

    private static void onActivityPaused(Activity activity) {
        if (activity == null || activity != currentActivity) return;
        lastResumeActivity = null;
        dismissPanel();
    }

    private static void onActivityStopped(Activity activity) {
        if (activity == null) return;
        if (activity == ballActivity) {
            detachBall();
        }
        if (activity == currentActivity) {
            dismissPanel();
            currentActivity = null;
            lastResumeActivity = null;
        }
    }

    private static void onActivityDestroyed(Activity activity) {
        if (activity == null) return;
        if (activity == ballActivity) {
            detachBall();
        }
        if (activity == currentActivity) {
            dismissPanel();
            currentActivity = null;
        }
    }

    private static void onActivityConfigurationChanged(Activity activity) {
        if (activity == null || activity != currentActivity) return;
        dismissPanel();
        if (ballView != null) ballView.post(FloatingUi::clampBall);
    }

    // ------------------------------------------------------------------
    // Ball attach / drag
    // ------------------------------------------------------------------

    private static AdapterConfig loadConfig(Activity activity) {
        File file = HostEnv.getConfigFile();
        if (file == null && activity != null) {
            File base = activity.getFilesDir();
            if (base != null) {
                file = new File(new File(base, AdapterConfig.DIR_NAME), AdapterConfig.FILE_NAME);
            }
        }
        config = AdapterConfig.load(file);
        return config;
    }

    private static void attachBall(Activity activity) {
        if (activity == null) return;
        if (ballView != null && ballActivity == activity) {
            clampBall();
            return;
        }
        if (ballView != null) detachBall();

        AdapterConfig.PackageConfig cfg = config != null
                ? config.getPackage(hostPackage) : new AdapterConfig.PackageConfig();
        int size = dp(activity, BALL_DP);
        ScreenInfo screen = screenInfo(activity);
        int x;
        int y;
        if (cfg.x < 0 || cfg.y < 0) {
            int[] pos = Geometry.defaultPosition(size, size, screen.width, screen.height,
                    screen.top, screen.bottom);
            x = pos[0];
            y = pos[1];
        } else {
            int[] pos = Geometry.clampPosition(cfg.x, cfg.y, size, size,
                    screen.width, screen.height, screen.top, screen.bottom);
            x = pos[0];
            y = pos[1];
        }

        TextView ball = createBall(activity);
        ballView = ball;
        ballActivity = activity;
        ball.setOnTouchListener(FloatingUi::onBallTouch);

        boolean attached = false;
        try {
            windowManager = activity.getWindowManager();
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = x;
            params.y = y;
            params.setTitle("DPIS UI Adapter");
            windowManager.addView(ball, params);
            ballParams = params;
            windowAttached = true;
            attached = true;
            UiLog.i("ball attached via WindowManager at " + x + "," + y
                    + " size=" + size + " (density=" + RealUi.density() + ")"
                    + " saved=" + (config != null ? config.getPackage(hostPackage).x + ","
                            + config.getPackage(hostPackage).y : "?")
                    + " uiState=" + (config != null && config.hasUiState(hostPackage)));
        } catch (Throwable throwable) {
            UiLog.w("WindowManager ball attach failed, using decor fallback: "
                    + throwable.getClass().getSimpleName());
        }

        if (!attached) {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                int[] location = new int[2];
                decor.getLocationOnScreen(location);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
                params.leftMargin = Math.max(0, x - location[0]);
                params.topMargin = Math.max(0, y - location[1]);
                decorOffsetX = location[0];
                decorOffsetY = location[1];
                decor.addView(ball, params);
                decorParent = decor;
                ballFrameParams = params;
                decorAttached = true;
                UiLog.i("ball attached via decor fallback");
            } catch (Throwable throwable) {
                UiLog.e("decor fallback ball attach failed", throwable);
                ballView = null;
                ballActivity = null;
            }
        }
        if (ballView != null) {
            installLayoutListener(activity);
            ballView.post(FloatingUi::clampBall);
        }
    }

    private static void installLayoutListener(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (layoutListener != null && layoutDecor == decor) return;
            removeLayoutListener();
            layoutListener = (v, left, top, right, bottom,
                              oldLeft, oldTop, oldRight, oldBottom) -> {
                if (currentActivity != null && ballView != null) {
                    ballView.post(FloatingUi::clampBall);
                }
            };
            decor.addOnLayoutChangeListener(layoutListener);
            layoutDecor = decor;
        } catch (Throwable throwable) {
            UiLog.w("layout listener install failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void removeLayoutListener() {
        if (layoutDecor != null && layoutListener != null) {
            try {
                layoutDecor.removeOnLayoutChangeListener(layoutListener);
            } catch (Throwable ignored) {
            }
        }
        layoutDecor = null;
        layoutListener = null;
    }

    private static TextView createBall(Context context) {
        // The sphere-and-menu icon from the app's SVG. It lives in this module's assets and
        // is decoded with the module class loader on purpose: a resource id would be
        // resolved against the HOST's resource table, which has no such entry (the first
        // attempt failed exactly that way with "Class alpha not found" from DrawableInflater).
        TextView view = new TextView(context);
        Bitmap ball = ballBitmap();
        if (ball != null) {
            view.setBackground(new BitmapDrawable(context.getResources(), ball));
        } else {
            GradientDrawable fallback = new GradientDrawable();
            fallback.setShape(GradientDrawable.OVAL);
            fallback.setColor(accentColor(context));
            view.setBackground(fallback);
        }
        view.setContentDescription("DPIS 配置悬浮球");
        view.setElevation(dp(context, 6));
        return view;
    }

    private static volatile Bitmap ballBitmap;

    /** Decoded once; null when the asset is missing so the caller can fall back. */
    private static Bitmap ballBitmap() {
        Bitmap cached = ballBitmap;
        if (cached != null) return cached;
        synchronized (FloatingUi.class) {
            if (ballBitmap != null) return ballBitmap;
            try (InputStream in = FloatingUi.class.getClassLoader()
                    .getResourceAsStream("assets/dpis_ball.png")) {
                if (in == null) {
                    UiLog.w("ball asset missing");
                    return null;
                }
                Bitmap decoded = BitmapFactory.decodeStream(in);
                if (decoded != null) {
                    decoded.setDensity(Bitmap.DENSITY_NONE);
                    ballBitmap = decoded;
                }
                return decoded;
            } catch (Throwable throwable) {
                UiLog.w("ball asset decode failed: " + throwable.getClass().getSimpleName());
                return null;
            }
        }
    }

    private static boolean onBallTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downBallX = ballX();
                downBallY = ballY();
                dragging = false;
                longPressFired = false;
                view.setPressed(true);
                if (handler != null && longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    handler.postDelayed(longPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging) {
                    int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        if (handler != null && longPressRunnable != null) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                    }
                }
                if (dragging) {
                    setBallPosition(downBallX + (int) dx, downBallY + (int) dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                cancelLongPress();
                view.setPressed(false);
                if (dragging) {
                    saveBallPosition();
                } else if (!longPressFired) {
                    togglePanel();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                view.setPressed(false);
                if (dragging) saveBallPosition();
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private static void cancelLongPress() {
        if (handler != null && longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
        }
    }

    private static int ballX() {
        if (windowAttached && ballParams != null) return ballParams.x;
        if (decorAttached && ballFrameParams != null) {
            return ballFrameParams.leftMargin + decorOffsetX;
        }
        return 0;
    }

    private static int ballY() {
        if (windowAttached && ballParams != null) return ballParams.y;
        if (decorAttached && ballFrameParams != null) {
            return ballFrameParams.topMargin + decorOffsetY;
        }
        return 0;
    }

    private static void setBallPosition(int x, int y) {
        if (currentActivity == null || ballView == null) return;
        if (windowAttached && ballParams != null) {
            ScreenInfo screen = screenInfo(currentActivity);
            int size = ballView.getWidth() > 0 ? ballView.getWidth() : dp(currentActivity, BALL_DP);
            int[] pos = Geometry.clampPosition(x, y, size, size,
                    screen.width, screen.height, screen.top, screen.bottom);
            if (pos[0] == ballParams.x && pos[1] == ballParams.y) return;
            ballParams.x = pos[0];
            ballParams.y = pos[1];
            try {
                windowManager.updateViewLayout(ballView, ballParams);
            } catch (Throwable throwable) {
                UiLog.w("updateViewLayout failed: " + throwable.getClass().getSimpleName());
            }
        } else if (decorAttached && ballFrameParams != null) {
            View parent = (View) ballView.getParent();
            int areaW = parent != null ? parent.getWidth() : 0;
            int areaH = parent != null ? parent.getHeight() : 0;
            int size = ballView.getWidth() > 0 ? ballView.getWidth() : dp(currentActivity, BALL_DP);
            int[] pos = Geometry.clampPosition(x, y, size, size,
                    areaW > 0 ? areaW : screenInfo(currentActivity).width,
                    areaH > 0 ? areaH : screenInfo(currentActivity).height, 0, 0);
            int newLeft = Math.max(0, pos[0] - decorOffsetX);
            int newTop = Math.max(0, pos[1] - decorOffsetY);
            if (newLeft == ballFrameParams.leftMargin
                    && newTop == ballFrameParams.topMargin) {
                return;
            }
            ballFrameParams.leftMargin = newLeft;
            ballFrameParams.topMargin = newTop;
            ballView.setLayoutParams(ballFrameParams);
        }
    }

    private static void clampBall() {
        if (currentActivity == null || ballView == null) return;
        if (decorAttached && decorParent != null) {
            try {
                int[] location = new int[2];
                decorParent.getLocationOnScreen(location);
                decorOffsetX = location[0];
                decorOffsetY = location[1];
            } catch (Throwable ignored) {
            }
        }
        setBallPosition(ballX(), ballY());
    }

    private static void saveBallPosition() {
        if (config == null || hostPackage == null) return;
        AdapterConfig.PackageConfig cfg = config.getPackage(hostPackage);
        config.saveUi(hostPackage, ballX(), ballY(), cfg.enabled);
    }

    private static void detachBall() {
        cancelLongPress();
        dismissConfirm();
        dismissPanel();
        if (ballView != null) {
            try {
                if (windowAttached && windowManager != null) {
                    windowManager.removeView(ballView);
                } else if (decorAttached && decorParent != null) {
                    decorParent.removeView(ballView);
                }
            } catch (Throwable throwable) {
                UiLog.w("detach ball failed: " + throwable.getClass().getSimpleName());
            }
        }
        ballView = null;
        ballActivity = null;
        ballParams = null;
        ballFrameParams = null;
        windowManager = null;
        decorParent = null;
        windowAttached = false;
        decorAttached = false;
        decorOffsetX = 0;
        decorOffsetY = 0;
        dragging = false;
        removeLayoutListener();
    }

    private static void hideBall(boolean persist) {
        if (config == null) {
            config = AdapterConfig.load(HostEnv.getConfigFile());
        }
        AdapterConfig.PackageConfig existing = config != null && hostPackage != null
                ? config.getPackage(hostPackage) : null;
        int x = ballView != null ? ballX() : (existing != null ? existing.x : 0);
        int y = ballView != null ? ballY() : (existing != null ? existing.y : 0);
        if (persist && config != null && hostPackage != null) {
            config.saveUi(hostPackage, x, y, false);
        }
        detachBall();
        String command = restoreCommand();
        toast("悬浮球已隐藏。连按音量下键 3 次可恢复；或执行：\n" + command);
        UiLog.i("ball hidden; restore with volume-down x3 or " + command);
    }

    private static void showBall() {
        if (config == null) config = AdapterConfig.load(HostEnv.getConfigFile());
        if (config != null && hostPackage != null) {
            AdapterConfig.PackageConfig cfg = config.getPackage(hostPackage);
            config.saveUi(hostPackage, cfg.x, cfg.y, true);
        }
        if (currentActivity != null) {
            attachBall(currentActivity);
        }
    }

    // ------------------------------------------------------------------
    // Panel
    // ------------------------------------------------------------------

    private static void togglePanel() {
        if (panelPopup != null && panelPopup.isShowing()) {
            dismissPanel();
        } else if (panelInDecor != null) {
            dismissPanel();
        } else {
            showPanel();
        }
    }

    private static void showPanel() {
        Activity activity = currentActivity;
        if (activity == null || ballView == null) return;
        if (config == null) config = loadConfig(activity);
        AdapterConfig.PackageConfig cfg = config.getPackage(hostPackage);

        PanelHolder panel = buildPanel(activity, cfg);
        ScreenInfo screen = screenInfo(activity);
        int width = Math.min(dp(activity, PANEL_WIDTH_DP),
                Math.max(dp(activity, 240), screen.width - dp(activity, 24)));
        int maxHeight = Math.max(dp(activity, 240),
                screen.height - screen.top - screen.bottom - dp(activity, 24));
        panel.scroll.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(panel.scroll.getMeasuredHeight(), maxHeight);
        if (height <= 0) height = maxHeight;

        // Fixed position: centred in the host window and deliberately independent of the
        // ball, so moving or hiding the ball can never move or resize the panel.
        View anchor = activity.getWindow().getDecorView();
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int offsetX = Math.max(0, (anchor.getWidth() - width) / 2);
        int offsetY = Math.max(0, (anchor.getHeight() - height) / 2);
        int left = anchorLocation[0] + offsetX;
        int top = anchorLocation[1] + offsetY;

        try {
            final PopupWindow popup = new PopupWindow(panel.scroll, width, height, true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);
            popup.setElevation(dp(activity, 10));
            popup.setOnDismissListener(() -> {
                restoreBallAfterOverlay();
                if (panelPopup == popup) {
                    panelPopup = null;
                    panelHolder = null;
                    if (panelInDecor != null) {
                        ViewGroup parent = (ViewGroup) panelInDecor.getParent();
                        if (parent != null) parent.removeView(panelInDecor);
                        panelInDecor = null;
                    }
                }
            });
            panelPopup = popup;
            panelHolder = panel;
            // Attach to the host window (correct window in split-screen) but at a fixed
            // offset inside it, never following the ball.
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, offsetX, offsetY);
            if (!popup.isShowing()) {
                throw new IllegalStateException("popup dismissed immediately");
            }
            hideBallForOverlay();
            UiLog.i("panel shown centered at " + left + "," + top + " size=" + width + "x" + height
                    + ", panelContext=" + RealUi.describe(panel.scroll.getContext())
                    + ", activityContext=" + RealUi.describe(activity));
        } catch (Throwable throwable) {
            panelPopup = null;
            UiLog.w("PopupWindow failed, using decor panel: "
                    + throwable.getClass().getSimpleName());
            showPanelInDecor(activity, panel.scroll, left, top, width, height);
        }
    }

    /**
     * Rebuilds the open panel from the current config.
     *
     * <p>{@code PopupWindow.setContentView} on a showing popup did not replace what was on
     * screen (verified on device: the new panel was built, the old labels stayed visible),
     * so the panel is dismissed and shown again at the same fixed, centred position. The
     * dismiss listener is detached first so the ball is not flashed back into view.</p>
     */
    private static void reopenPanel() {
        Activity activity = currentActivity;
        if (activity == null) return;
        PopupWindow popup = panelPopup;
        panelPopup = null;
        panelHolder = null;
        if (popup != null) {
            try {
                popup.setOnDismissListener(null);
                if (popup.isShowing()) popup.dismiss();
            } catch (Throwable ignored) {
            }
        }
        if (config == null) config = AdapterConfig.load(HostEnv.getConfigFile());
        showPanel();
        UiLog.i("panel reopened after reset");
    }

    private static void showPanelInDecor(Activity activity, View content,
                                         int left, int top, int width, int height) {
        try {
            ViewParent oldParent = content.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(content);
            }
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            int[] location = new int[2];
            decor.getLocationOnScreen(location);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.leftMargin = Math.max(0, left - location[0]);
            params.topMargin = Math.max(0, top - location[1]);
            decor.addView(content, params);
            panelInDecor = content;
            hideBallForOverlay();
            UiLog.i("panel shown via decor fallback");
        } catch (Throwable throwable) {
            UiLog.e("decor panel attach failed", throwable);
            toast("无法打开配置面板");
        }
    }

    /** Counts volume-down presses: three inside the window toggle the ball. */
    private static void onHostKeyEvent(KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return;
        if (panelPopup != null || panelInDecor != null || confirmPopup != null) {
            // An overlay owns the foreground; do not fight it for the gesture.
            volumeDownCount = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastVolumeDownAt > GESTURE_WINDOW_MS) volumeDownCount = 0;
        lastVolumeDownAt = now;
        if (++volumeDownCount < GESTURE_PRESS_COUNT) return;
        volumeDownCount = 0;
        toggleBallByGesture();
    }

    private static void toggleBallByGesture() {
        Activity activity = currentActivity;
        if (activity == null) return;
        AdapterConfig.PackageConfig cfg = config != null ? config.getPackage(hostPackage) : null;
        boolean visible = ballView != null && cfg != null && cfg.enabled;
        if (visible) {
            hideBall(true);
            UiLog.i("volume-down x3: ball hidden");
            return;
        }
        if (config == null) config = AdapterConfig.load(HostEnv.getConfigFile());
        if (config != null && hostPackage != null) {
            config.setUiEnabled(hostPackage, true);
        }
        showBall();
        toastOn(activity, "悬浮球已显示");
        UiLog.i("volume-down x3: ball shown");
    }

    private static String restoreCommand() {
        return "adb shell am broadcast -a " + ACTION_SHOW
                + " -p " + (hostPackage != null ? hostPackage : "<host>")
                + " --es token " + TOKEN;
    }

    /**
     * Long-press confirmation before hiding the ball. Fixed size and centred exactly like
     * the panel, and it spells out both ways back: the volume gesture and the ADB command.
     */
    private static void showHideConfirm() {
        Activity activity = currentActivity;
        if (activity == null || confirmPopup != null) return;
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bgColor = night ? 0xF21C1C1C : 0xF7FFFFFF;
        int strokeColor = night ? 0x33FFFFFF : 0x22000000;
        int textColor = night ? 0xFFF2F2F2 : 0xFF1A1A1A;
        int subColor = night ? 0xFFA0A0A0 : 0xFF666666;
        int accent = night ? 0xFF64B5F6 : 0xFF1E88E5;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 18);
        root.setPadding(padding, dp(activity, 16), padding, dp(activity, 16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(dp(activity, 16));
        background.setStroke(dp(activity, 1), strokeColor);
        root.setBackground(background);

        TextView title = text(activity, "隐藏悬浮球？", 17, textColor, true);
        root.addView(title);
        TextView body = text(activity, "隐藏后球会消失，可用下面任一方式恢复：", 13, subColor, false);
        body.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        root.addView(body);

        TextView gesture = text(activity, "① 连按音量下键 3 次", 13, textColor, false);
        root.addView(gesture);

        TextView commandLabel = text(activity, "② 或执行 ADB 命令：", 13, textColor, false);
        commandLabel.setPadding(0, dp(activity, 10), 0, dp(activity, 6));
        root.addView(commandLabel);

        TextView command = text(activity, restoreCommand(), 10, subColor, false);
        command.setTypeface(Typeface.MONOSPACE);
        command.setTextIsSelectable(true);
        command.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        GradientDrawable commandBg = new GradientDrawable();
        commandBg.setColor(night ? 0x1FFFFFFF : 0x0F000000);
        commandBg.setCornerRadius(dp(activity, 8));
        command.setBackground(commandBg);
        root.addView(command, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(activity, 14), 0, 0);
        TextView cancel = button(activity, "取消", textColor, 0, strokeColor);
        TextView hide = button(activity, "隐藏", Color.WHITE, accent, 0);
        buttons.addView(cancel, weighted());
        buttons.addView(space(activity, 8));
        buttons.addView(hide, weighted());
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScreenInfo screen = screenInfo(activity);
        int margin = dp(activity, 16);
        int width = Math.min(dp(activity, PANEL_WIDTH_DP),
                Math.max(dp(activity, 240), screen.width - margin * 2));
        int maxHeight = Math.max(dp(activity, 200),
                screen.height - screen.top - screen.bottom - margin * 2);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(root.getMeasuredHeight(), maxHeight);
        if (height <= 0) height = maxHeight;

        View anchor = activity.getWindow().getDecorView();
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int offsetX = Math.max(0, (anchor.getWidth() - width) / 2);
        int offsetY = Math.max(0, (anchor.getHeight() - height) / 2);

        hideBallForOverlay();
        try {
            final PopupWindow popup = new PopupWindow(root, width, height, true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            popup.setFocusable(true);
            popup.setElevation(dp(activity, 12));
            popup.setOnDismissListener(() -> {
                restoreBallAfterOverlay();
                if (confirmPopup == popup) confirmPopup = null;
            });
            confirmPopup = popup;
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, offsetX, offsetY);
            cancel.setOnClickListener(v -> dismissConfirm());
            hide.setOnClickListener(v -> {
                dismissConfirm();
                hideBall(true);
            });
            UiLog.i("hide confirm shown: size=" + width + "x" + height
                    + ", offset=" + offsetX + "," + offsetY);
        } catch (Throwable throwable) {
            confirmPopup = null;
            restoreBallAfterOverlay();
            UiLog.w("hide confirm failed, hiding directly: "
                    + throwable.getClass().getSimpleName());
            hideBall(true);
        }
    }

    private static void dismissConfirm() {
        PopupWindow popup = confirmPopup;
        confirmPopup = null;
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hideBallForOverlay() {
        if (ballView == null || ballHiddenForOverlay) return;
        ballView.setVisibility(View.GONE);
        ballHiddenForOverlay = true;
    }

    private static void restoreBallAfterOverlay() {
        if (!ballHiddenForOverlay) return;
        ballHiddenForOverlay = false;
        if (ballView != null) ballView.setVisibility(View.VISIBLE);
    }

    private static void dismissPanel() {
        restoreBallAfterOverlay();
        PopupWindow popup = panelPopup;
        panelPopup = null;
        panelHolder = null;
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
        }
        if (panelInDecor != null) {
            try {
                ViewGroup parent = (ViewGroup) panelInDecor.getParent();
                if (parent != null) parent.removeView(panelInDecor);
            } catch (Throwable ignored) {
            }
            panelInDecor = null;
        }
    }

    private static final class PanelHolder {
        final ScrollView scroll;
        final UiControls.Toggle dpisEnabled;
        final TextView relative;
        final TextView absolute;
        final UiControls.Slider scale;
        final UiControls.Slider width;
        final UiControls.Slider font;
        final UiControls.Toggle logEnabled;
        final TextView fontModeAuto;
        final TextView fontModeSystem;
        final TextView fontModeCompat;
        final TextView scaleLabel;
        final TextView widthLabel;
        final TextView fontLabel;
        final View scaleRow;
        final View widthRow;

        PanelHolder(ScrollView scroll, UiControls.Toggle dpisEnabled,
                    TextView relative, TextView absolute,
                    UiControls.Slider scale, UiControls.Slider width,
                    UiControls.Slider font, UiControls.Toggle logEnabled,
                    TextView fontModeAuto, TextView fontModeSystem, TextView fontModeCompat,
                    TextView scaleLabel, TextView widthLabel, TextView fontLabel,
                    View scaleRow, View widthRow) {
            this.scroll = scroll;
            this.dpisEnabled = dpisEnabled;
            this.relative = relative;
            this.absolute = absolute;
            this.scale = scale;
            this.width = width;
            this.font = font;
            this.logEnabled = logEnabled;
            this.fontModeAuto = fontModeAuto;
            this.fontModeSystem = fontModeSystem;
            this.fontModeCompat = fontModeCompat;
            this.scaleLabel = scaleLabel;
            this.widthLabel = widthLabel;
            this.fontLabel = fontLabel;
            this.scaleRow = scaleRow;
            this.widthRow = widthRow;
        }

        AdapterConfig.PackageConfig read() {
            AdapterConfig.PackageConfig cfg = new AdapterConfig.PackageConfig();
            cfg.dpisEnabled = dpisEnabled.isChecked();
            cfg.viewportMode = absolute.isSelected()
                    ? AdapterConfig.MODE_ABSOLUTE : AdapterConfig.MODE_RELATIVE;
            cfg.scalePercent = scale.getProgress();
            cfg.widthDp = width.getProgress();
            cfg.fontScalePercent = font.getProgress();
            cfg.fontMode = fontModeCompat.isSelected() ? AdapterConfig.FONT_MODE_COMPAT
                    : fontModeSystem.isSelected() ? AdapterConfig.FONT_MODE_SYSTEM
                    : AdapterConfig.FONT_MODE_AUTO;
            cfg.logEnabled = logEnabled.isChecked();
            return cfg;
        }
    }

    private static PanelHolder buildPanel(Activity activity,
                                          AdapterConfig.PackageConfig cfg) {
        // Every size below is computed in real pixels (dp/spPx), and the interactive
        // controls are drawn by UiControls. Platform widgets are avoided on purpose: their
        // intrinsic sizes come from the theme's (DPIS-overridden) density, so a Switch or
        // SeekBar would grow and shrink with the very value this panel sets.
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bgColor = night ? 0xF21C1C1C : 0xF7FFFFFF;
        int strokeColor = night ? 0x33FFFFFF : 0x22000000;
        int textColor = night ? 0xFFF2F2F2 : 0xFF1A1A1A;
        int subColor = night ? 0xFFA0A0A0 : 0xFF666666;
        int accent = night ? 0xFF64B5F6 : 0xFF1E88E5;
        int trackColor = night ? 0x33FFFFFF : 0x33666666;
        int thumbColor = night ? 0xFFE8E8E8 : 0xFFFFFFFF;
        Context context = activity;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        root.setPadding(padding, dp(context, 14), padding, dp(context, 14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(dp(context, 16));
        background.setStroke(dp(context, 1), strokeColor);
        root.setBackground(background);
        root.setElevation(dp(context, 10));

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(context, "DPIS 配置", 18, textColor, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text(context, "×", 26, subColor, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(context, 8), 0, dp(context, 4), 0);
        close.setOnClickListener(v -> dismissPanel());
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.setPadding(0, 0, 0, dp(context, 10));
        root.addView(divider(context, strokeColor), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));

        // DPIS enable
        LinearLayout dpisRow = new LinearLayout(context);
        dpisRow.setOrientation(LinearLayout.HORIZONTAL);
        dpisRow.setGravity(Gravity.CENTER_VERTICAL);
        dpisRow.setPadding(0, dp(context, 8), 0, dp(context, 8));
        TextView dpisLabel = text(context, "启用 DPIS", 14, textColor, false);
        dpisRow.addView(dpisLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        UiControls.Toggle dpisToggle = new UiControls.Toggle(context,
                dp(context, 46), dp(context, 26), accent, trackColor, thumbColor);
        dpisToggle.setChecked(cfg.dpisEnabled);
        dpisRow.addView(dpisToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(dpisRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Mode
        TextView modeLabel = text(context, "缩放模式", 13, subColor, false);
        modeLabel.setPadding(0, dp(context, 6), 0, dp(context, 4));
        root.addView(modeLabel);
        LinearLayout modeRow = new LinearLayout(context);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView relative = button(context, "相对比例", textColor, 0, strokeColor);
        TextView absolute = button(context, "绝对宽度", textColor, 0, strokeColor);
        styleSegment(relative, cfg.isRelativeMode(), accent, textColor, strokeColor);
        styleSegment(absolute, !cfg.isRelativeMode(), accent, textColor, strokeColor);
        modeRow.addView(relative, weighted());
        modeRow.addView(space(context, 8));
        modeRow.addView(absolute, weighted());
        root.addView(modeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Scale
        LinearLayout scaleRow = new LinearLayout(context);
        scaleRow.setOrientation(LinearLayout.VERTICAL);
        TextView scaleLabel = text(context, "", 13, textColor, false);
        scaleLabel.setPadding(0, dp(context, 6), 0, 0);
        UiControls.Slider scale = slider(context, AdapterConfig.MIN_SCALE_PERCENT,
                AdapterConfig.MAX_SCALE_PERCENT, cfg.scalePercent, accent, trackColor, thumbColor);
        scaleLabel.setText("界面缩放 " + cfg.scalePercent + "%");
        scale.setOnChanged((bar, value, fromUser) ->
                scaleLabel.setText("界面缩放 " + value + "%"));
        scaleRow.addView(scaleLabel);
        scaleRow.addView(scale, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scaleRow);

        // Absolute width
        LinearLayout widthRow = new LinearLayout(context);
        widthRow.setOrientation(LinearLayout.VERTICAL);
        TextView widthLabel = text(context, "", 13, textColor, false);
        widthLabel.setPadding(0, dp(context, 6), 0, 0);
        UiControls.Slider width = slider(context, AdapterConfig.MIN_WIDTH_DP,
                AdapterConfig.MAX_WIDTH_DP, cfg.widthDp, accent, trackColor, thumbColor);
        widthLabel.setText("最小宽度 " + cfg.widthDp + " dp");
        width.setOnChanged((bar, value, fromUser) ->
                widthLabel.setText("最小宽度 " + value + " dp"));
        widthRow.addView(widthLabel);
        widthRow.addView(width, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(widthRow);

        // Font
        LinearLayout fontRow = new LinearLayout(context);
        fontRow.setOrientation(LinearLayout.VERTICAL);
        TextView fontLabel = text(context, "", 13, textColor, false);
        fontLabel.setPadding(0, dp(context, 6), 0, 0);
        UiControls.Slider font = slider(context, AdapterConfig.MIN_FONT_PERCENT,
                AdapterConfig.MAX_FONT_PERCENT, cfg.fontScalePercent, accent, trackColor, thumbColor);
        fontLabel.setText("字体缩放 " + cfg.fontScalePercent + "%");
        font.setOnChanged((bar, value, fromUser) ->
                fontLabel.setText("字体缩放 " + value + "%"));
        fontRow.addView(fontLabel);
        fontRow.addView(font, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(fontRow);

        // Font apply mode: DPIS's own UI calls these "系统" / "兼容"; the compat route is
        // the documented workaround when font scaling makes the host janky.
        TextView fontModeLabel = text(context, "字体模式", 13, subColor, false);
        fontModeLabel.setPadding(0, dp(context, 6), 0, dp(context, 4));
        root.addView(fontModeLabel);
        LinearLayout fontModeRow = new LinearLayout(context);
        fontModeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView fontModeAuto = button(context, "自动", textColor, 0, strokeColor);
        TextView fontModeSystem = button(context, "系统", textColor, 0, strokeColor);
        TextView fontModeCompat = button(context, "兼容", textColor, 0, strokeColor);
        fontModeRow.addView(fontModeAuto, weighted());
        fontModeRow.addView(space(context, 8));
        fontModeRow.addView(fontModeSystem, weighted());
        fontModeRow.addView(space(context, 8));
        fontModeRow.addView(fontModeCompat, weighted());
        root.addView(fontModeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Log switch
        LinearLayout logRow = new LinearLayout(context);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(context, 8), 0, dp(context, 4));
        TextView logLabel = text(context, "DPIS 详细日志", 14, textColor, false);
        logRow.addView(logLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        UiControls.Toggle logToggle = new UiControls.Toggle(context,
                dp(context, 46), dp(context, 26), accent, trackColor, thumbColor);
        logToggle.setChecked(cfg.logEnabled);
        logRow.addView(logToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Hint
        TextView hint = text(context,
                "保存后请完全关闭并重新启动应用以生效。", 12, accent, false);
        hint.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(night ? 0x22264A6B : 0x141E88E5);
        hintBg.setCornerRadius(dp(context, 8));
        hint.setBackground(hintBg);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(context, 8);
        root.addView(hint, hintParams);

        // Buttons
        LinearLayout buttonRow1 = new LinearLayout(context);
        buttonRow1.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow1.setPadding(0, dp(context, 12), 0, 0);
        TextView save = button(context, "保存", Color.WHITE, accent, 0);
        TextView reset = button(context, "重置", textColor, 0, strokeColor);
        buttonRow1.addView(save, weighted());
        buttonRow1.addView(space(context, 8));
        buttonRow1.addView(reset, weighted());
        root.addView(buttonRow1);

        LinearLayout buttonRow2 = new LinearLayout(context);
        buttonRow2.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow2.setPadding(0, dp(context, 8), 0, 0);
        TextView export = button(context, "导出备份", textColor, 0, strokeColor);
        TextView hide = button(context, "隐藏悬浮球", textColor, 0, strokeColor);
        buttonRow2.addView(export, weighted());
        buttonRow2.addView(space(context, 8));
        buttonRow2.addView(hide, weighted());
        root.addView(buttonRow2);

        LinearLayout buttonRow3 = new LinearLayout(context);
        buttonRow3.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow3.setPadding(0, dp(context, 8), 0, 0);
        TextView saveRestart = button(context, "保存并关闭应用", accent, 0, accent);
        buttonRow3.addView(saveRestart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buttonRow3);

        // Keep the way back visible in the panel itself, not only in the hide toast.
        TextView restoreTitle = text(context, "悬浮球恢复方法", 11, subColor, true);
        restoreTitle.setPadding(0, dp(context, 14), 0, dp(context, 4));
        root.addView(restoreTitle);
        TextView restoreBody = text(context,
                "① 连按音量下键 3 次\n② 或执行：" + restoreCommand(), 10, subColor, false);
        restoreBody.setLineSpacing(dp(context, 2), 1f);
        restoreBody.setTextIsSelectable(true);
        root.addView(restoreBody);

        // Behavior
        Runnable updateModeVisibility = () -> {
            boolean absoluteMode = absolute.isSelected();
            scaleRow.setVisibility(absoluteMode ? View.GONE : View.VISIBLE);
            widthRow.setVisibility(absoluteMode ? View.VISIBLE : View.GONE);
        };
        View.OnClickListener modeClick = v -> {
            boolean absoluteMode = v == absolute;
            styleSegment(relative, !absoluteMode, accent, textColor, strokeColor);
            styleSegment(absolute, absoluteMode, accent, textColor, strokeColor);
            updateModeVisibility.run();
        };
        relative.setOnClickListener(modeClick);
        absolute.setOnClickListener(modeClick);
        updateModeVisibility.run();

        List<TextView> fontModes = Arrays.asList(fontModeAuto, fontModeSystem, fontModeCompat);
        TextView initialFontMode = AdapterConfig.FONT_MODE_COMPAT.equals(cfg.fontMode)
                ? fontModeCompat
                : AdapterConfig.FONT_MODE_SYSTEM.equals(cfg.fontMode)
                        ? fontModeSystem : fontModeAuto;
        selectSegment(fontModes, initialFontMode, accent, textColor, strokeColor);
        for (TextView option : fontModes) {
            option.setOnClickListener(v -> selectSegment(fontModes, (TextView) v,
                    accent, textColor, strokeColor));
        }

        final PanelHolder[] panelRef = new PanelHolder[1];

        save.setOnClickListener(v -> {
            AdapterConfig.PackageConfig updated = panelRef[0].read();
            if (!config.savePackage(hostPackage, updated)) {
                toast("保存失败：无法写入 " + hostPackage + " 的私有配置");
                UiLog.e("config save failed", new IllegalStateException("write returned false"));
                return;
            }
            DpisConfigBridge.reload();
            toast("配置已保存，请完全关闭并重新启动应用以生效");
            UiLog.i("config saved: host=" + hostPackage
                    + ", mode=" + updated.viewportMode
                    + ", scale=" + updated.scalePercent
                    + ", width=" + updated.widthDp
                    + ", font=" + updated.fontScalePercent
                    + ", dpis=" + updated.dpisEnabled);
            dismissPanel();
        });

        reset.setOnClickListener(v -> {
            if (!config.resetPackage(hostPackage)
                    || !config.setUiEnabled(hostPackage, true)) {
                toast("重置失败：无法写入配置");
                return;
            }
            DpisConfigBridge.reload();
            // Keep the panel up: reload the (now default) values and rebuild its content in
            // the same popup, so the user sees what the reset produced without reopening.
            config = AdapterConfig.load(HostEnv.getConfigFile());
            reopenPanel();
            toast("已重置为默认配置，请重启应用");
            UiLog.i("config reset: host=" + hostPackage);
        });

        export.setOnClickListener(v -> exportBackup(activity, panelRef[0].read()));
        // Same confirmation as the long press: the panel is closed first (it would sit
        // behind the dialog and would also fight the ball-visibility bookkeeping), then
        // the shared confirm dialog decides.
        hide.setOnClickListener(v -> {
            dismissPanel();
            showHideConfirm();
        });
        saveRestart.setOnClickListener(v -> saveAndClose(panelRef[0].read()));
        close.setOnClickListener(v -> dismissPanel());

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PanelHolder panel = new PanelHolder(scroll, dpisToggle, relative, absolute,
                scale, width, font, logToggle, fontModeAuto, fontModeSystem, fontModeCompat,
                scaleLabel, widthLabel, fontLabel, scaleRow, widthRow);
        panelRef[0] = panel;
        return panel;
    }

    private static UiControls.Slider slider(Context context, int min, int max, int value,
                                            int accent, int trackColor, int thumbColor) {
        return new UiControls.Slider(context, min, max, value,
                dp(context, 44), dp(context, 4), dp(context, 9),
                accent, trackColor, thumbColor);
    }

    private static void selectSegment(List<TextView> group, TextView chosen,
                                      int accent, int textColor, int strokeColor) {
        for (TextView view : group) {
            styleSegment(view, view == chosen, accent, textColor, strokeColor);
        }
    }

    /**
     * Saves and relaunches the host. DPIS takes its configuration snapshot in
     * onPackageReady, so a restart is the only way to apply a saved value; the alarm keeps
     * the relaunch pending while this process dies, which is what makes the system rebuild
     * the task in a fresh process.
     */
    private static void saveAndClose(AdapterConfig.PackageConfig updated) {
        if (!config.savePackage(hostPackage, updated)) {
            toast("保存失败：无法写入配置");
            return;
        }
        DpisConfigBridge.reload();
        UiLog.i("save + close: host=" + hostPackage
                + ", scale=" + updated.scalePercent
                + ", fontMode=" + updated.fontMode);
        dismissPanel();
        // A real relaunch is not possible from inside the hooked process: starting the
        // activity hands the launch back to this very process, and an alarm-based launch
        // needs SCHEDULE_EXACT_ALARM (SecurityException without it — verified on device)
        // or a manifest component this module does not own. So: save, then close, and the
        // user reopens — one tap instead of hunting for "force stop".
        toast("配置已保存，应用已关闭，请重新打开以生效");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 500L);
    }

    private static void styleSegment(TextView view, boolean selected,
                                     int accent, int textColor, int strokeColor) {
        Context context = view.getContext();
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 10));
        if (selected) {
            background.setColor(accent);
            view.setTextColor(Color.WHITE);
        } else {
            background.setColor(Color.TRANSPARENT);
            background.setStroke(dp(context, 1), strokeColor);
            view.setTextColor(textColor);
        }
        view.setBackground(background);
        view.setSelected(selected);
    }

    private static View divider(Context context, int color) {
        View view = new View(context);
        view.setBackgroundColor(color);
        return view;
    }

    private static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(context, sp));
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static TextView button(Context context, String value, int textColor,
                                   int fillColor, int strokeColor) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(context, 14));
        view.setTextColor(textColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 10));
        if (fillColor != 0) {
            background.setColor(fillColor);
        } else {
            background.setColor(Color.TRANSPARENT);
        }
        if (strokeColor != 0) {
            background.setStroke(dp(context, 1), strokeColor);
        }
        view.setBackground(background);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static Space space(Context context, int dp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(context, dp), 1));
        return space;
    }

    // ------------------------------------------------------------------
    // Backup export
    // ------------------------------------------------------------------

    private static void exportBackup(Activity activity, AdapterConfig.PackageConfig cfg) {
        try {
            DpisMeta meta = config != null ? config.getDpisMeta() : DpisMeta.defaults();
            String json = DpisBackup.build(hostPackage, cfg, meta);
            String name = "dpis-backup-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".json";
            File dir = HostEnv.getExternalBackupDir(activity);
            String path = null;
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                File out = new File(dir, name);
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    stream.write(json.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                    try {
                        stream.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                }
                path = out.getAbsolutePath();
            }
            pendingExportJson = json;
            pendingExportPath = path;
            toast(path != null ? "备份副本已写入：\n" + path : "正在打开导出位置…");

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, name);
            activity.startActivityForResult(intent, REQ_EXPORT);
            UiLog.i("backup generated: " + (path != null ? path : "(no external dir)"));
        } catch (Throwable throwable) {
            UiLog.e("backup export failed", throwable);
            toast("导出失败：" + throwable.getClass().getSimpleName());
        }
    }

    private static void onActivityResult(Activity activity, int requestCode,
                                         int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) return;
        String json = pendingExportJson;
        String path = pendingExportPath;
        pendingExportJson = null;
        pendingExportPath = null;
        if (json == null) return;
        Uri uri = data != null ? data.getData() : null;
        if (resultCode == Activity.RESULT_OK && uri != null) {
            new Thread(() -> {
                String error = null;
                try (OutputStream stream = activity.getContentResolver()
                        .openOutputStream(uri)) {
                    if (stream == null) throw new IllegalStateException("no output stream");
                    stream.write(json.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                } catch (Throwable throwable) {
                    error = throwable.getClass().getSimpleName();
                }
                final String failure = error;
                activity.runOnUiThread(() -> {
                    if (failure == null) {
                        toast("备份已导出");
                    } else {
                        toast("写入失败：" + failure
                                + (path != null ? "\n副本：" + path : ""));
                    }
                });
            }, "dpis-ui-backup").start();
        } else {
            toast(path != null ? "已取消；备份副本保存在：\n" + path : "已取消导出");
        }
    }

    // ------------------------------------------------------------------
    // Broadcast recovery / headless config
    // ------------------------------------------------------------------

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) return;
        appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    handleBroadcast(ctx, intent);
                } catch (Throwable throwable) {
                    UiLog.e("broadcast handling failed", throwable);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_HIDE);
        filter.addAction(ACTION_TOGGLE);
        filter.addAction(ACTION_RESET);
        filter.addAction(ACTION_CONFIG);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
            UiLog.i("control receiver registered: " + appContext.getPackageName());
        } catch (Throwable throwable) {
            UiLog.e("control receiver registration failed", throwable);
        }
    }

    private static void handleBroadcast(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (TextUtils.isEmpty(action)) return;
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (!TOKEN.equals(token)) {
            UiLog.w("ignoring broadcast without the adapter token");
            return;
        }
        String host = HostEnv.getPackageName();
        if (host == null && currentActivity != null) host = currentActivity.getPackageName();
        if (host == null) {
            UiLog.w("broadcast ignored: host unknown");
            return;
        }
        hostPackage = host;
        if (config == null) config = AdapterConfig.load(HostEnv.getConfigFile());
        AdapterConfig.PackageConfig cfg = config.getPackage(host);

        switch (action) {
            case ACTION_SHOW:
                config.saveUi(host, cfg.x, cfg.y, true);
                showBall();
                toastOn(context, "悬浮球已显示");
                break;
            case ACTION_HIDE:
                hideBall(true);
                break;
            case ACTION_TOGGLE:
                if (cfg.enabled) {
                    hideBall(true);
                } else {
                    config.saveUi(host, cfg.x, cfg.y, true);
                    showBall();
                    toastOn(context, "悬浮球已显示");
                }
                break;
            case ACTION_RESET: {
                if (!config.resetPackage(host)
                        || !config.setUiEnabled(host, true)) {
                    toastOn(context, "重置失败：无法写入配置");
                    break;
                }
                DpisConfigBridge.reload();
                config = AdapterConfig.load(HostEnv.getConfigFile());
                dismissPanel();
                showBall();
                toastOn(context, "已重置为默认配置，请重启宿主应用");
                UiLog.i("reset via broadcast: " + host);
                break;
            }
            case ACTION_CONFIG: {
                boolean changed = false;
                if (intent.hasExtra(EXTRA_DPIS_ENABLED)) {
                    cfg.dpisEnabled = intent.getBooleanExtra(EXTRA_DPIS_ENABLED, true);
                    changed = true;
                }
                if (intent.hasExtra(EXTRA_MODE)) {
                    cfg.viewportMode = AdapterConfig.normalizeMode(
                            intent.getStringExtra(EXTRA_MODE));
                    changed = true;
                }
                if (intent.hasExtra(EXTRA_SCALE)) {
                    cfg.scalePercent = AdapterConfig.clamp(
                            intent.getIntExtra(EXTRA_SCALE, cfg.scalePercent),
                            AdapterConfig.MIN_SCALE_PERCENT, AdapterConfig.MAX_SCALE_PERCENT);
                    changed = true;
                }
                if (intent.hasExtra(EXTRA_WIDTH)) {
                    cfg.widthDp = AdapterConfig.clamp(
                            intent.getIntExtra(EXTRA_WIDTH, cfg.widthDp),
                            AdapterConfig.MIN_WIDTH_DP, AdapterConfig.MAX_WIDTH_DP);
                    changed = true;
                }
                if (intent.hasExtra(EXTRA_FONT)) {
                    cfg.fontScalePercent = AdapterConfig.clamp(
                            intent.getIntExtra(EXTRA_FONT, cfg.fontScalePercent),
                            AdapterConfig.MIN_FONT_PERCENT, AdapterConfig.MAX_FONT_PERCENT);
                    changed = true;
                }
                if (intent.hasExtra(EXTRA_LOG_ENABLED)) {
                    cfg.logEnabled = intent.getBooleanExtra(EXTRA_LOG_ENABLED, cfg.logEnabled);
                    changed = true;
                }
                if (!changed) {
                    toastOn(context, "没有可应用的配置参数");
                    break;
                }
                if (!config.savePackage(host, cfg)) {
                    toastOn(context, "写入失败：无法保存配置");
                    break;
                }
                DpisConfigBridge.reload();
                toastOn(context, "配置已写入，请重启宿主应用");
                UiLog.i("config via broadcast: host=" + host
                        + ", mode=" + cfg.viewportMode + ", scale=" + cfg.scalePercent);
                break;
            }
            default:
                UiLog.w("unknown broadcast action: " + action);
        }
    }

    // ------------------------------------------------------------------
    // Geometry / screen / misc
    // ------------------------------------------------------------------

    private static final class ScreenInfo {
        final int width;
        final int height;
        final int top;
        final int bottom;

        ScreenInfo(int width, int height, int top, int bottom) {
            this.width = width;
            this.height = height;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static ScreenInfo screenInfo(Activity activity) {
        int width;
        int height;
        int top = 0;
        int bottom = 0;
        // Display.getRealMetrics is one of the methods DPIS hooks and reports the scaled
        // size there, so prefer what initZygote captured from the physical display.
        width = RealUi.width();
        height = RealUi.height();
        if (width <= 0 || height <= 0) {
            try {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            } catch (Throwable throwable) {
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            }
        }
        try {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
            }
        } catch (Throwable ignored) {
        }
        if (width <= 0) width = 1080;
        if (height <= 0) height = 1920;
        return new ScreenInfo(width, height, top, bottom);
    }

    /**
     * Size in <b>real</b> screen pixels.
     *
     * <p>Deliberately not the context's density: DPIS overrides the host's display
     * metrics, so a context-derived dp would make this overlay scale with the very
     * value it is meant to control. {@link RealUi} captured the physical density
     * before DPIS installed its hooks.</p>
     */
    private static int dp(Context context, int value) {
        float density = RealUi.density();
        if (density <= 0f) {
            density = context != null
                    ? context.getResources().getDisplayMetrics().density : 3f;
        }
        return Math.round(value * density);
    }

    /** Scaled-pixel text size, pinned to the real display for the same reason. */
    private static float spPx(Context context, float value) {
        float scaled = RealUi.scaledDensity();
        if (scaled <= 0f) {
            scaled = context != null
                    ? context.getResources().getDisplayMetrics().scaledDensity : 3f;
        }
        return value * scaled;
    }

    private static int accentColor(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFF64B5F6 : 0xFF1E88E5;
    }

    private static void toast(String message) {
        Context context = currentActivity != null ? currentActivity : appContext;
        toastOn(context, message);
    }

    private static void toastOn(Context context, String message) {
        if (context == null) return;
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            UiLog.e("toast failed", throwable);
        }
    }
}
