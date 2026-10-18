package com.dpis.uiadapter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-host adapter configuration.
 *
 * <p>The file lives in the host app's private data directory, so it is already isolated by
 * host package. Every package is nevertheless stored under {@code packages.<host>} and
 * {@code ui.<host>}, which keeps the format usable if it is ever copied between hosts.</p>
 *
 * <p>This class is deliberately Android-free so the merge/validation rules can be unit
 * tested on the JVM.</p>
 */
public final class AdapterConfig {

    public static final String DIR_NAME = "dpis_ui_adapter";
    public static final String FILE_NAME = "config.json";
    public static final String MODE_RELATIVE = "relative";
    public static final String MODE_ABSOLUTE = "absolute";

    /** DPIS font apply mode: let DPIS decide, or force one of its two hook routes. */
    public static final String FONT_MODE_AUTO = "auto";
    public static final String FONT_MODE_SYSTEM = "system";
    public static final String FONT_MODE_COMPAT = "compat";

    public static final int MIN_SCALE_PERCENT = 30;
    public static final int MAX_SCALE_PERCENT = 300;
    public static final int MIN_WIDTH_DP = 200;
    public static final int MAX_WIDTH_DP = 2000;
    public static final int MIN_FONT_PERCENT = 50;
    public static final int MAX_FONT_PERCENT = 300;

    private static final String ASSET_DEFAULTS = "assets/dpis_adapter_defaults.json";

    public static final class PackageConfig {
        /** Whether the floating ball should be displayed. */
        public boolean enabled = true;
        /** Whether DPIS itself is enabled for this host. */
        public boolean dpisEnabled = true;
        public String viewportMode = MODE_RELATIVE;
        public int scalePercent = 100;
        public int widthDp = 360;
        public int fontScalePercent = 100;

        /** One of {@link #FONT_MODE_AUTO}, {@link #FONT_MODE_SYSTEM}, {@link #FONT_MODE_COMPAT}. */
        public String fontMode = FONT_MODE_AUTO;
        public boolean logEnabled = false;

        /** Saved ball position, in screen pixels. -1 means "not positioned yet". */
        public int x = -1;
        public int y = -1;

        /** True when the package has an entry in the user's config file. */
        public boolean explicit = false;

        public PackageConfig copy() {
            PackageConfig out = new PackageConfig();
            out.mergeFrom(this, explicit);
            out.x = x;
            out.y = y;
            return out;
        }

        public void mergeFrom(PackageConfig other, boolean markExplicit) {
            if (other == null) return;
            enabled = other.enabled;
            dpisEnabled = other.dpisEnabled;
            viewportMode = normalizeMode(other.viewportMode);
            scalePercent = clamp(other.scalePercent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
            widthDp = clamp(other.widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP);
            fontScalePercent = clamp(other.fontScalePercent, MIN_FONT_PERCENT, MAX_FONT_PERCENT);
            fontMode = normalizeFontMode(other.fontMode);
            logEnabled = other.logEnabled;
            if (markExplicit) explicit = true;
        }

        public boolean isRelativeMode() {
            return MODE_RELATIVE.equals(viewportMode);
        }
    }

    private final File file;
    private final JSONObject asset;
    private final JSONObject root;
    private final PackageConfig defaults;
    private final Map<String, PackageConfig> assetPackages = new LinkedHashMap<>();
    private final DpisMeta dpisMeta;
    private boolean fileExists;
    /** True when the file exists but could not be parsed; writes must not clobber it. */
    private final boolean loadFailed;

    private AdapterConfig(File file, JSONObject asset) {
        this.file = file;
        this.asset = asset != null ? asset : new JSONObject();
        this.dpisMeta = DpisMeta.from(this.asset.optJSONObject("dpis"));
        this.defaults = parsePackage(this.asset.optJSONObject("defaults"), new PackageConfig());

        JSONObject packages = this.asset.optJSONObject("packages");
        if (packages != null) {
            Iterator<String> keys = packages.keys();
            while (keys.hasNext()) {
                String host = keys.next();
                JSONObject value = packages.optJSONObject(host);
                if (host == null || host.isEmpty() || value == null) continue;
                PackageConfig pkg = parsePackage(value, defaults);
                assetPackages.put(host, pkg);
            }
        }

        this.fileExists = file != null && file.isFile();
        JSONObject loaded = null;
        boolean unreadable = false;
        if (fileExists) {
            try {
                loaded = new JSONObject(readText(file));
            } catch (Throwable ignored) {
                loaded = null;
                // The file is there but we cannot parse it. Treat the config as
                // unavailable instead of "empty": writing defaults now would replace a
                // user's saved package configuration with the bundled asset values.
                unreadable = true;
            }
        }
        this.loadFailed = unreadable;
        this.root = loaded != null ? loaded : new JSONObject();
        if (!this.root.has("schema")) {
            try {
                this.root.put("schema", 1);
            } catch (JSONException ignored) {
            }
        }
    }

    public static AdapterConfig load(File file) {
        AdapterConfig config = new AdapterConfig(file, readAsset(ASSET_DEFAULTS));
        // A config that silently lost its "packages" section is the difference between
        // "the user's 70%" and "the bundled 80%", so every load is traceable.
        try {
            UiLog.i("config load: " + (file == null ? "null" : file.getAbsolutePath())
                    + " exists=" + (file != null && file.isFile())
                    + " len=" + (file != null && file.isFile() ? file.length() : 0)
                    + " failed=" + config.loadFailed
                    + " rootKeys=" + config.root.length()
                    + " packages=" + (config.root.optJSONObject("packages") != null));
        } catch (Throwable ignored) {
        }
        return config;
    }

    public File getFile() {
        return file;
    }

    public boolean fileExists() {
        return fileExists;
    }

    /** True when the file carries a saved ball position/enabled state for this host. */
    public boolean hasUiState(String host) {
        JSONObject ui = root.optJSONObject("ui");
        return ui != null && ui.optJSONObject(host) != null;
    }

    /** The file exists but could not be read: the loaded values are not the user's. */
    public boolean loadFailed() {
        return loadFailed;
    }

    public DpisMeta getDpisMeta() {
        return dpisMeta;
    }

    public PackageConfig getDefaults() {
        return defaults.copy();
    }

    public boolean hasExplicitPackage(String host) {
        JSONObject packages = root.optJSONObject("packages");
        return packages != null && packages.has(host);
    }

    public boolean hasAssetDefault(String host) {
        return assetPackages.containsKey(host);
    }

    /**
     * The overlay is only active when the user saved a package or when the bundled
     * asset explicitly opts that package in (this build ships no per-package entry).
     * Merely dragging the ball writes only ui.* and must not enable DPIS.
     */
    public boolean shouldOverlay(String host) {
        return hasExplicitPackage(host) || hasAssetDefault(host);
    }

    /** Effective config, merging hardcoded defaults, asset per-package defaults and user file. */
    public PackageConfig getPackage(String host) {
        PackageConfig merged = defaults.copy();
        PackageConfig assetPkg = assetPackages.get(host);
        if (assetPkg != null) {
            merged.mergeFrom(assetPkg, false);
        }
        JSONObject packages = root.optJSONObject("packages");
        JSONObject userPkg = packages != null ? packages.optJSONObject(host) : null;
        if (userPkg != null) {
            PackageConfig parsed = parsePackage(userPkg, merged);
            merged.mergeFrom(parsed, false);
            merged.explicit = true;
        }
        JSONObject ui = root.optJSONObject("ui");
        JSONObject userUi = ui != null ? ui.optJSONObject(host) : null;
        if (userUi != null) {
            merged.enabled = userUi.optBoolean("enabled", merged.enabled);
            merged.x = userUi.optInt("x", merged.x);
            merged.y = userUi.optInt("y", merged.y);
        }
        return merged;
    }

    public boolean savePackage(String host, PackageConfig config) {
        if (host == null || host.isEmpty() || config == null) return false;
        try {
            JSONObject packages = root.optJSONObject("packages");
            if (packages == null) {
                packages = new JSONObject();
                root.put("packages", packages);
            }
            JSONObject pkg = new JSONObject();
            pkg.put("dpis_enabled", config.dpisEnabled);
            pkg.put("viewport_mode", normalizeMode(config.viewportMode));
            pkg.put("scale_percent", clamp(config.scalePercent,
                    MIN_SCALE_PERCENT, MAX_SCALE_PERCENT));
            pkg.put("width_dp", clamp(config.widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP));
            pkg.put("font_scale_percent", clamp(config.fontScalePercent,
                    MIN_FONT_PERCENT, MAX_FONT_PERCENT));
            pkg.put("font_mode", normalizeFontMode(config.fontMode));
            pkg.put("log_enabled", config.logEnabled);
            pkg.put("updated_at", System.currentTimeMillis());
            packages.put(host, pkg);
            config.explicit = true;
            write();
            return true;
        } catch (Throwable ignored) {
            // Callers surface a toast; a failed write leaves the previous file intact.
            return false;
        }
    }

    public boolean saveUi(String host, int x, int y, boolean enabled) {
        if (host == null || host.isEmpty()) return false;
        try {
            JSONObject ui = root.optJSONObject("ui");
            if (ui == null) {
                ui = new JSONObject();
                root.put("ui", ui);
            }
            JSONObject state = ui.optJSONObject(host);
            if (state == null) {
                state = new JSONObject();
                ui.put(host, state);
            }
            state.put("x", x);
            state.put("y", y);
            state.put("enabled", enabled);
            state.put("updated_at", System.currentTimeMillis());
            write();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean setUiEnabled(String host, boolean enabled) {
        PackageConfig current = getPackage(host);
        return saveUi(host, current.x, current.y, enabled);
    }

    public boolean resetPackage(String host) {
        if (host == null || host.isEmpty()) return false;
        try {
            JSONObject packages = root.optJSONObject("packages");
            if (packages != null) {
                packages.remove(host);
            }
            write();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void write() throws IOException, JSONException {
        if (file == null) throw new IOException("No config file");
        if (loadFailed) {
            // Never replace a file we failed to parse: the in-memory root is defaults,
            // so writing it would silently drop the user's saved settings.
            throw new IOException("Refusing to overwrite unreadable config: " + file);
        }
        // A config object loaded while the file was being replaced can carry only defaults.
        // Writing it would erase the user's per-package settings, so recover the sections
        // from disk first: a save may add to them, never drop them wholesale. (The reset
        // path keeps both objects and only removes one package, so this cannot undo it.)
        if (file.isFile()) {
            boolean missingPackages = root.optJSONObject("packages") == null;
            boolean missingUi = root.optJSONObject("ui") == null;
            if (missingPackages || missingUi) {
                try {
                    JSONObject disk = new JSONObject(readText(file));
                    if (missingPackages) {
                        JSONObject packages = disk.optJSONObject("packages");
                        if (packages != null) root.put("packages", packages);
                    }
                    if (missingUi) {
                        JSONObject ui = disk.optJSONObject("ui");
                        if (ui != null) root.put("ui", ui);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        root.put("schema", 1);
        root.put("updated_at", System.currentTimeMillis());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Cannot create config directory: " + parent);
        }
        byte[] payload = root.toString(2).getBytes(StandardCharsets.UTF_8);
        try {
            UiLog.i("config write: " + file.getAbsolutePath() + " len=" + payload.length
                    + " packages=" + (root.optJSONObject("packages") != null)
                    + " ui=" + (root.optJSONObject("ui") != null));
        } catch (Throwable ignored) {
        }
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(payload);
            out.flush();
            try {
                out.getFD().sync();
            } catch (Throwable ignored) {
                // sync() is not supported on every Android filesystem; flush + rename
                // is still atomic enough for this small file.
            }
        }
        if (!temp.renameTo(file)) {
            // Some Android filesystems refuse rename over an existing file. Fall back to a
            // direct copy; the file is tiny and this keeps the update from being lost.
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(payload);
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Throwable ignored) {
                }
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        fileExists = true;
    }

    static PackageConfig parsePackage(JSONObject object, PackageConfig base) {
        PackageConfig out = base.copy();
        if (object == null) return out;
        out.dpisEnabled = object.optBoolean("dpis_enabled", out.dpisEnabled);
        out.viewportMode = normalizeMode(object.optString("viewport_mode", out.viewportMode));
        out.scalePercent = clamp(object.optInt("scale_percent", out.scalePercent),
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        out.widthDp = clamp(object.optInt("width_dp", out.widthDp),
                MIN_WIDTH_DP, MAX_WIDTH_DP);
        out.fontScalePercent = clamp(object.optInt("font_scale_percent",
                out.fontScalePercent), MIN_FONT_PERCENT, MAX_FONT_PERCENT);
        out.fontMode = normalizeFontMode(object.optString("font_mode", out.fontMode));
        out.logEnabled = object.optBoolean("log_enabled", out.logEnabled);
        out.enabled = object.optBoolean("enabled", out.enabled);
        out.x = object.optInt("x", out.x);
        out.y = object.optInt("y", out.y);
        return out;
    }

    /** Maps DPIS's stored values ("system_emulation" / "field_rewrite") to this module's names. */
    static String normalizeFontMode(String mode) {
        if (mode == null) return FONT_MODE_AUTO;
        String value = mode.trim().toLowerCase(java.util.Locale.US);
        if (FONT_MODE_SYSTEM.equals(value) || "system_emulation".equals(value)) {
            return FONT_MODE_SYSTEM;
        }
        if (FONT_MODE_COMPAT.equals(value) || "field_rewrite".equals(value)) {
            return FONT_MODE_COMPAT;
        }
        return FONT_MODE_AUTO;
    }

    static String normalizeMode(String mode) {
        if (mode == null) return MODE_RELATIVE;
        String value = mode.trim().toLowerCase(java.util.Locale.US);
        if (MODE_ABSOLUTE.equals(value) || "absolute_dp".equals(value) || "width".equals(value)) {
            return MODE_ABSOLUTE;
        }
        return MODE_RELATIVE;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readText(in);
        }
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static JSONObject readAsset(String name) {
        try {
            ClassLoader loader = AdapterConfig.class.getClassLoader();
            InputStream in = loader != null ? loader.getResourceAsStream(name) : null;
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(name);
            }
            if (in == null) return null;
            try (InputStream stream = in) {
                return new JSONObject(readText(stream));
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
