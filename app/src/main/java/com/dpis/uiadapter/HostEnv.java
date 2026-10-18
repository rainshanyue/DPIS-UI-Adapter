package com.dpis.uiadapter;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Resolves the host package / data directory without a module Application.
 *
 * <p>DPIS's modern runtime reads its config during {@code onPackageReady}, which is
 * before the host's Application is created. The ActivityThread's bound application
 * record is already available at that point, so the read-time hook can locate the
 * host's private config file without waiting for an Activity.</p>
 */
public final class HostEnv {

    private static volatile String packageName;
    private static volatile String processName;
    private static volatile String dataDir;

    private HostEnv() {
    }

    public static void setFromLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;
        packageName = lpparam.packageName;
        processName = lpparam.processName;
        ApplicationInfo info = lpparam.appInfo;
        if (info != null) {
            dataDir = info.dataDir;
        }
    }

    public static void updateFromActivity(Activity activity) {
        if (activity == null) return;
        try {
            ApplicationInfo info = activity.getApplicationInfo();
            if (info != null) {
                packageName = info.packageName;
                dataDir = info.dataDir;
            }
            packageName = activity.getPackageName();
        } catch (Throwable ignored) {
        }
    }

    public static String getPackageName() {
        ensureResolved();
        return packageName;
    }

    public static String getProcessName() {
        ensureResolved();
        return processName;
    }

    public static String getDataDir() {
        ensureResolved();
        return dataDir;
    }

    public static boolean isMainProcess() {
        ensureResolved();
        String pkg = packageName;
        String proc = processName;
        if (pkg == null) return false;
        return proc == null || proc.equals(pkg) || proc.equals(pkg + ":main");
    }

    public static File getConfigFile() {
        String dir = getDataDir();
        if (dir == null || dir.isEmpty()) return null;
        return new File(new File(dir, "files"), AdapterConfig.DIR_NAME + File.separator
                + AdapterConfig.FILE_NAME);
    }

    public static File getExternalBackupDir(Activity activity) {
        try {
            File base = activity.getExternalFilesDir(null);
            if (base == null) base = activity.getFilesDir();
            if (base == null) return null;
            return new File(base, "dpis-backup");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureResolved() {
        if (packageName != null && dataDir != null) return;
        synchronized (HostEnv.class) {
            if (packageName != null && dataDir != null) return;
            resolveFromActivityThread();
            if (packageName == null) resolveFromProcessName();
            if (dataDir == null && packageName != null) {
                for (String candidate : new String[]{
                        "/data/data/" + packageName,
                        "/data/user/0/" + packageName,
                }) {
                    File file = new File(candidate);
                    if (file.isDirectory()) {
                        dataDir = file.getAbsolutePath();
                        break;
                    }
                }
            }
        }
    }

    private static void resolveFromActivityThread() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = XposedHelpers.callStaticMethod(
                    activityThreadClass, "currentActivityThread");
            if (activityThread != null) {
                try {
                    Object boundApplication = XposedHelpers.getObjectField(
                            activityThread, "mBoundApplication");
                    if (boundApplication != null) {
                        Object appInfo = XposedHelpers.getObjectField(boundApplication, "appInfo");
                        if (appInfo instanceof ApplicationInfo) {
                            ApplicationInfo info = (ApplicationInfo) appInfo;
                            if (info.packageName != null) packageName = info.packageName;
                            if (info.dataDir != null) dataDir = info.dataDir;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (dataDir == null || packageName == null) {
                    try {
                        Object application = XposedHelpers.getObjectField(
                                activityThread, "mInitialApplication");
                        if (application instanceof Application) {
                            Application app = (Application) application;
                            if (packageName == null) packageName = app.getPackageName();
                            if (dataDir == null) {
                                ApplicationInfo info = app.getApplicationInfo();
                                if (info != null && info.dataDir != null) dataDir = info.dataDir;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String proc = Application.getProcessName();
                if (proc != null && !proc.isEmpty()) processName = proc;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void resolveFromProcessName() {
        String proc = processName;
        if (proc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                proc = Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        if (proc == null || proc.isEmpty()) {
            proc = readProcCmdline();
        }
        processName = proc;
        if (packageName == null && proc != null && !proc.isEmpty()) {
            int colon = proc.indexOf(':');
            packageName = colon > 0 ? proc.substring(0, colon) : proc;
        }
    }

    private static String readProcCmdline() {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    new File("/proc/self/cmdline").toPath());
            int length = 0;
            while (length < bytes.length && bytes[length] != 0) length++;
            return new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
