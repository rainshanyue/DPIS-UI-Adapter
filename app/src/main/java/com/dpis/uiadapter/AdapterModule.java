package com.dpis.uiadapter;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed entry point.
 *
 * <p>A legacy module is used deliberately: LSPatch loads legacy modules during
 * {@code Startup.bootstrapXposed()}, before it loads modern (libxposed API 101/102)
 * modules such as DPIS Modern. That gives {@link #initZygote} a guaranteed
 * before-DPIS-read installation point without a module Application.</p>
 *
 * <p>All UI and config work happens from these two callbacks and the hooks they
 * install; the module never relies on its own process.</p>
 */
public final class AdapterModule implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    @Override
    public void initZygote(StartupParam startupParam) {
        UiLog.i("initZygote: installing DPIS config bridge, modulePath="
                + (startupParam != null ? startupParam.modulePath : "?"));
        // Must happen before DPIS rewrites the app's display metrics (see RealUi).
        RealUi.capture();
        try {
            DpisConfigBridge.install();
        } catch (Throwable throwable) {
            UiLog.e("initZygote bridge installation failed", throwable);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        String packageName = lpparam.packageName;
        if ("android".equals(packageName) || "system".equals(packageName)) return;
        if (!TargetPolicy.get().matches(packageName)) {
            UiLog.i("host not in target allow-list: " + packageName);
            return;
        }
        try {
            HostEnv.setFromLoadPackage(lpparam);
            UiLog.i("handleLoadPackage: package=" + packageName
                    + ", process=" + lpparam.processName
                    + ", first=" + lpparam.isFirstApplication);
            // DPIS took its snapshot in onPackageReady, before this callback; the early
            // seed in initZygote is what takes effect for this launch. This only heals a
            // missed early seed for the next one.
            DpisConfigBridge.onHostLoaded(packageName);
            if (HostEnv.isMainProcess()) {
                FloatingUi.installActivityHooks(lpparam);
            } else {
                UiLog.i("secondary process: config bridge active, UI skipped");
            }
        } catch (Throwable throwable) {
            // A UI failure must never take the host down.
            UiLog.e("handleLoadPackage setup failed", throwable);
        }
    }
}
