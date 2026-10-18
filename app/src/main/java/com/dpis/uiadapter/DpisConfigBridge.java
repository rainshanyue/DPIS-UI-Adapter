package com.dpis.uiadapter;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Injects the adapter's per-host values into DPIS's configuration store.
 *
 * <p><b>Why the store and not a hook?</b> Vector refuses to hook any method whose
 * declaring class lives in the framework's own class loader:
 * {@code de.robv.android.xposed.XposedBridge.hookMethod} throws
 * {@code IllegalArgumentException: ... belongs to Vector itself}. That rules out
 * {@code VectorContext.getRemotePreferences}, {@code VectorRemotePreferences.*} and
 * {@code XSharedPreferences} — the whole "hook the read path" approach.</p>
 *
 * <p>The framework does, however, expose a supported write path that needs no hook at
 * all: LSPatch keeps a module's remote preferences in a SQLite table
 * ({@code configs} in {@code databases/lspatch-xposed-remote.db}, the same schema
 * Vector's daemon uses). {@code VectorRemotePreferences} is built from that table, so
 * anything already stored there when DPIS first reads its preferences is served to it
 * as ordinary configuration.</p>
 *
 * <p>The timing constraint is strict and is why this runs from {@code initZygote}
 * (inside {@code Startup.bootstrapXposed}) rather than from {@code handleLoadPackage}:
 * LSPatch dispatches the package lifecycle as {@code onPackageLoaded} →
 * {@code onPackageReady} → legacy {@code handleLoadPackage}. DPIS loads its config
 * snapshot in {@code onPackageReady}, so by the time a legacy module sees the host the
 * snapshot has already been taken. {@code bootstrapXposed} runs before
 * {@code loadModulesAndDeliver}, i.e. before the DPIS module is even instantiated.</p>
 *
 * <p><b>Why the legacy key namespace?</b> The DPIS APK ships
 * {@code assets/dpis_default_config.json}, and {@code BundledDefaultConfigSeeder}
 * rewrites every key that document declares on each start — typically the aggregated
 * {@code package_config.<host>.viewport.*} form. Values seeded into those
 * keys would be overwritten a moment later. {@code PackageConfigReader} prefers the
 * historical {@code viewport.<pkg>.*} form whenever it is present
 * ({@code packageNullableInt(legacyKey, packageKey)}), and the bundled document does
 * not mention those keys at all, so they survive the seed and win the read. Both
 * namespaces are still written so the store stays self-consistent.</p>
 */
public final class DpisConfigBridge {

    /** DPIS's module package: the row key LSPatch stores a module's preferences under. */
    private static final String DPIS_MODULE_PACKAGE = "io.github.kwensiu.dpis";

    private static final String DB_RELATIVE_PATH = "databases/lspatch-xposed-remote.db";
    private static final String TABLE = "configs";
    private static final String KEY_TARGET_PACKAGES = "target_packages";

    private static final Object LOCK = new Object();

    private static volatile AdapterConfig config;
    private static volatile OverlayValues overlay;
    private static volatile String overlayHost;
    private static volatile File overlayFile;
    private static volatile boolean overlayResolved;
    private static volatile boolean overlayLogged;
    private static volatile boolean seededThisProcess;

    private DpisConfigBridge() {
    }

    /**
     * Runs in {@code initZygote}, before LSPatch instantiates the DPIS module, so the
     * seeded rows are present the first time DPIS reads its preferences.
     */
    public static void install() {
        try {
            String host = HostEnv.getPackageName();
            if (host == null || host.isEmpty()) {
                UiLog.w("config seed skipped: host package unknown at initZygote");
                return;
            }
            if (!TargetPolicy.get().matches(host)) {
                UiLog.i("config seed skipped: host not in target allow-list: " + host);
                return;
            }
            if (!HostEnv.isMainProcess()) {
                // Secondary processes read the same store; only the main process seeds it.
                UiLog.i("config seed skipped in secondary process: " + HostEnv.getProcessName());
                return;
            }
            seededThisProcess = seed(host, "initZygote");
        } catch (Throwable throwable) {
            // Never let a config failure take the host down during bootstrap.
            UiLog.e("config seed failed at initZygote", throwable);
        }
    }

    /**
     * Called from {@code handleLoadPackage}. By now DPIS has already taken its snapshot
     * for this launch, so this is a self-heal for the next start if the early seed could
     * not run (unknown data dir, locked database, ...).
     */
    public static void onHostLoaded(String host) {
        if (host == null || host.isEmpty() || seededThisProcess) return;
        // Only the main process seeds: every process of the host shares the same store, so
        // a secondary-process write would add startup I/O and lock contention for a value
        // that is already there.
        if (!HostEnv.isMainProcess()) return;
        try {
            seededThisProcess = seed(host, "handleLoadPackage (next start)");
        } catch (Throwable throwable) {
            UiLog.e("late config seed failed", throwable);
        }
    }

    /** Called by the UI after it writes the config file. */
    public static void reload() {
        synchronized (LOCK) {
            config = null;
            overlay = null;
            overlayHost = null;
            overlayFile = null;
            overlayResolved = false;
            overlayLogged = false;
        }
        // The running host already read its snapshot; a save takes effect on the next
        // start, so push the new values into the store now.
        try {
            String host = HostEnv.getPackageName();
            if (host != null && TargetPolicy.get().matches(host)) {
                seed(host, "reload");
            }
        } catch (Throwable throwable) {
            UiLog.e("reload seed failed", throwable);
        }
        UiLog.i("adapter config cache invalidated");
    }

    public static AdapterConfig getConfig() {
        AdapterConfig local = config;
        if (local != null) return local;
        synchronized (LOCK) {
            if (config == null) {
                config = AdapterConfig.load(HostEnv.getConfigFile());
            }
            return config;
        }
    }

    public static OverlayValues currentOverlay() {
        String host = HostEnv.getPackageName();
        if (host == null || host.isEmpty()) return null;
        if (!TargetPolicy.get().matches(host)) return null;

        File file = HostEnv.getConfigFile();
        if (overlayResolved && host.equals(overlayHost)
                && (file == null ? overlayFile == null : file.equals(overlayFile))) {
            return overlay;
        }
        synchronized (LOCK) {
            if (overlayResolved && host.equals(overlayHost)
                    && (file == null ? overlayFile == null : file.equals(overlayFile))) {
                return overlay;
            }
            AdapterConfig loaded = AdapterConfig.load(file);
            config = loaded;
            overlayHost = host;
            overlayFile = file;
            if (!loaded.shouldOverlay(host)) {
                overlay = null;
                overlayResolved = true;
                return null;
            }
            AdapterConfig.PackageConfig pkg = loaded.getPackage(host);
            boolean includeGlobal = loaded.hasExplicitPackage(host);
            OverlayValues values = new OverlayValues(host, pkg, includeGlobal);
            overlay = values;
            overlayResolved = true;
            if (!overlayLogged) {
                overlayLogged = true;
                UiLog.i("config overlay active: host=" + host
                        + ", mode=" + pkg.viewportMode
                        + ", scale=" + pkg.scalePercent + "%"
                        + ", width=" + pkg.widthDp + "dp"
                        + ", font=" + pkg.fontScalePercent + "%"
                        + ", dpisEnabled=" + pkg.dpisEnabled
                        + ", explicit=" + loaded.hasExplicitPackage(host));
            }
            return values;
        }
    }

    // ---------------------------------------------------------------- seeding

    private static boolean seed(String host, String reason) {
        File dbFile = resolveStoreFile();
        if (dbFile == null) {
            UiLog.w("config seed skipped: host data dir unknown");
            return false;
        }
        AdapterConfig cfg = AdapterConfig.load(HostEnv.getConfigFile());
        if (cfg.loadFailed()) {
            // The file exists but is unreadable; seeding now would replace DPIS's stored
            // values with the bundled asset defaults and lose the user's configuration.
            UiLog.w("config seed skipped: existing config file unreadable, keeping stored values");
            return false;
        }
        boolean overlay = cfg.shouldOverlay(host);

        Map<String, Object> puts = new LinkedHashMap<>();
        Set<String> deletes = new LinkedHashSet<>();
        buildPlan(host, cfg, overlay, puts, deletes);

        if (overlay) {
            // DPIS only looks at packages it considers configured; make sure this host is
            // in the catalogue even when the bundled asset did not put it there.
            puts.put(KEY_TARGET_PACKAGES, null); // placeholder, merged inside the store
        }

        String readback = writeStore(dbFile, puts, deletes, host);
        UiLog.i("seeded DPIS store (" + reason + "): db=" + dbFile.getAbsolutePath()
                + ", host=" + host
                + ", overlay=" + overlay
                + ", file=" + (cfg.fileExists() ? "read" : "absent")
                + ", uiState=" + cfg.hasUiState(host)
                + ", explicit=" + cfg.hasExplicitPackage(host)
                + ", puts=" + puts.size()
                + ", deletes=" + deletes.size()
                + ", readback=" + readback);
        return true;
    }

    private static void buildPlan(String host, AdapterConfig cfg, boolean overlay,
                                  Map<String, Object> puts, Set<String> deletes) {
        AdapterConfig.PackageConfig pkg = cfg.getPackage(host);
        if (!overlay) {
            // Nothing should be injected: remove anything a previous save left behind,
            // in both namespaces, for every domain this adapter can manage.
            deletes.addAll(new OverlayValues(host, relativeProbe(pkg), false).managedKeys());
            deletes.addAll(new OverlayValues(host, absoluteProbe(pkg), false).managedKeys());
            return;
        }
        OverlayValues values = new OverlayValues(host, pkg, cfg.hasExplicitPackage(host));
        for (String key : values.managedKeys()) {
            Object value = values.value(key);
            if (value != null && !values.isHidden(key)) {
                puts.put(key, value);
            } else {
                deletes.add(key);
            }
        }
    }

    /** A copy of the config forced into relative mode, used only to enumerate keys. */
    private static AdapterConfig.PackageConfig relativeProbe(AdapterConfig.PackageConfig base) {
        AdapterConfig.PackageConfig probe = base.copy();
        probe.viewportMode = AdapterConfig.MODE_RELATIVE;
        return probe;
    }

    /** A copy of the config forced into absolute mode, used only to enumerate keys. */
    private static AdapterConfig.PackageConfig absoluteProbe(AdapterConfig.PackageConfig base) {
        AdapterConfig.PackageConfig probe = base.copy();
        probe.viewportMode = AdapterConfig.MODE_ABSOLUTE;
        return probe;
    }

    private static File resolveStoreFile() {
        String dataDir = HostEnv.getDataDir();
        if (dataDir == null || dataDir.isEmpty()) return null;
        return new File(new File(dataDir, "databases"), "lspatch-xposed-remote.db");
    }

    private static int userId() {
        return android.os.Process.myUid() / 100000;
    }

    /**
     * Applies the plan to LSPatch's preference table and returns the values read back,
     * which is what turns "the write returned" into evidence the store really holds them.
     */
    private static String writeStore(File dbFile, Map<String, Object> puts,
                                     Set<String> deletes, String host) {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            UiLog.w("config seed: cannot create database directory " + parent);
            return "no-db-dir";
        }

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY
                            | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "module_pkg_name TEXT NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "`group` TEXT NOT NULL,"
                    + "`key` TEXT NOT NULL,"
                    + "data BLOB,"
                    + "PRIMARY KEY (module_pkg_name, user_id, `group`, `key`))");

            int user = userId();
            String group = OverlayValues.GROUP;

            db.beginTransaction();
            try {
                for (String key : deletes) {
                    db.delete(TABLE,
                            "module_pkg_name = ? AND user_id = ? AND `group` = ? AND `key` = ?",
                            new String[]{DPIS_MODULE_PACKAGE, Integer.toString(user), group, key});
                }
                for (Map.Entry<String, Object> entry : puts.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (value == null && KEY_TARGET_PACKAGES.equals(key)) {
                        value = mergedTargetPackages(db, user, group, host);
                    }
                    if (value == null) continue;
                    byte[] blob = serialize(value);
                    if (blob == null) continue;
                    ContentValues values = new ContentValues();
                    values.put("module_pkg_name", DPIS_MODULE_PACKAGE);
                    values.put("user_id", user);
                    values.put("`group`", group);
                    values.put("`key`", key);
                    values.put("data", blob);
                    db.insertWithOnConflict(TABLE, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return describeStored(db, user, group, host);
        } catch (Throwable throwable) {
            UiLog.e("config seed: store write failed", throwable);
            return "write-failed:" + throwable.getClass().getSimpleName();
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Serializable mergedTargetPackages(SQLiteDatabase db, int user, String group,
                                                     String host) {
        Set<String> packages = new LinkedHashSet<>();
        try (Cursor cursor = db.query(TABLE, new String[]{"data"},
                "module_pkg_name = ? AND user_id = ? AND `group` = ? AND `key` = ?",
                new String[]{DPIS_MODULE_PACKAGE, Integer.toString(user), group,
                        KEY_TARGET_PACKAGES},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                Object value = deserialize(cursor.getBlob(0));
                if (value instanceof Set) {
                    for (Object item : (Set<?>) value) {
                        if (item instanceof String && !((String) item).isEmpty()) {
                            packages.add((String) item);
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            UiLog.w("config seed: reading target_packages failed: "
                    + throwable.getClass().getSimpleName());
        }
        if (packages.contains(host)) return null; // already listed; leave the row alone
        packages.add(host);
        return new LinkedHashSet<>(packages);
    }

    private static String describeStored(SQLiteDatabase db, int user, String group, String host) {
        StringBuilder out = new StringBuilder();
        try (Cursor cursor = db.query(TABLE, new String[]{"`key`", "data"},
                "module_pkg_name = ? AND user_id = ? AND `group` = ?",
                new String[]{DPIS_MODULE_PACKAGE, Integer.toString(user), group},
                null, null, "`key`")) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                if (!isManagedKey(key)) continue;
                Object value = deserialize(cursor.getBlob(1));
                if (out.length() > 0) out.append(';');
                out.append(stripHost(key, host)).append('=').append(describe(value));
            }
        } catch (Throwable throwable) {
            return "readback-failed:" + throwable.getClass().getSimpleName();
        }
        return out.length() == 0 ? "empty" : out.toString();
    }

    private static boolean isManagedKey(String key) {
        return key != null && (key.startsWith("package_config.")
                || key.startsWith("viewport.")
                || key.startsWith("font.")
                || key.startsWith("target."));
    }

    /** Shortens {@code viewport.<host>.x} to {@code viewport.x} for the log line. */
    private static String stripHost(String key, String host) {
        if (host == null || host.isEmpty()) return key;
        return key.replace(host + ".", "");
    }

    private static String describe(Object value) {
        if (value instanceof Set) {
            return "set" + value;
        }
        return String.valueOf(value);
    }

    private static byte[] serialize(Object value) {
        if (!(value instanceof Serializable)) return null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            return bytes.toByteArray();
        } catch (Throwable throwable) {
            UiLog.w("config seed: serialize failed for " + value
                    + ": " + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static Object deserialize(byte[] blob) {
        if (blob == null) return null;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
            return in.readObject();
        } catch (Throwable throwable) {
            return null;
        }
    }
}
