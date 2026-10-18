package com.dpis.uiadapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The read-time projection of one host's adapter config over DPIS's read-only
 * remote preference store.
 *
 * <p>Only keys owned by the current host are managed. Everything else in DPIS's
 * store (other packages, system-server switches, typeface ids, ...) is passed
 * through untouched.</p>
 */
public final class OverlayValues {

    public static final String GROUP = "dpi_config";
    public static final String KEY_TARGET_PACKAGES = "target_packages";
    public static final String KEY_GLOBAL_LOG = "global.log_enabled";

    private static final String PACKAGE_PREFIX = "package_config.";

    private final String host;
    private final AdapterConfig.PackageConfig config;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Set<String> managed = new LinkedHashSet<>();

    public OverlayValues(String host, AdapterConfig.PackageConfig config,
                         boolean includeGlobal) {
        this.host = host;
        this.config = config;

        // DPIS still accepts both its historical keys (viewport.<pkg>.*) and the
        // aggregated keys (package_config.<pkg>.viewport.*); PackageConfigReader
        // prefers the historical form when it is present. Manage both namespaces
        // so a stale value from an older DPIS GUI cannot win over this UI.
        hidePair("viewport", "mode");
        // DPIS exposes two font hook routes ("系统" / "兼容"). "auto" leaves the key
        // absent so PackageConfigReader falls back to SYSTEM_EMULATION, which is what
        // this adapter did before the choice existed.
        if (AdapterConfig.FONT_MODE_AUTO.equals(config.fontMode)) {
            hidePair("font", "mode");
        } else {
            valuePair("font", "mode",
                    AdapterConfig.FONT_MODE_COMPAT.equals(config.fontMode)
                            ? "field_rewrite" : "system_emulation");
        }
        if (includeGlobal) {
            managed.add(KEY_GLOBAL_LOG);
        }

        if (config.isRelativeMode()) {
            valuePair("viewport", "target_type", "relative_scale");
            valuePair("viewport", "scale_milli_percent", config.scalePercent * 1000);
            valuePair("viewport", "scale_permille", config.scalePercent * 10);
            hidePair("viewport", "width_dp");
        } else {
            valuePair("viewport", "target_type", "absolute_dp");
            valuePair("viewport", "width_dp", config.widthDp);
            hidePair("viewport", "scale_permille");
            hidePair("viewport", "scale_milli_percent");
        }

        if (config.fontScalePercent == 100) {
            // 100% means "leave the host font alone"; hiding the key stops DPIS from
            // enabling a needless font hook route.
            hidePair("font", "scale_percent");
        } else {
            valuePair("font", "scale_percent", config.fontScalePercent);
        }

        if (config.dpisEnabled) {
            // The runtime defaults the toggle to true. Omitting the key is the cleanest
            // representation of "enabled" and also keeps the package from being marked
            // configured solely by a true toggle.
            hidePair("target", "dpis_enabled");
        } else {
            valuePair("target", "dpis_enabled", Boolean.FALSE);
        }

        if (includeGlobal) {
            values.put(KEY_GLOBAL_LOG, config.logEnabled);
        }
    }

    public String getHost() {
        return host;
    }

    public AdapterConfig.PackageConfig getConfig() {
        return config;
    }

    public boolean isManaged(String key) {
        return key != null && (managed.contains(key) || KEY_TARGET_PACKAGES.equals(key));
    }

    /**
     * Every key this host's overlay owns, in both the historical and the aggregated
     * namespace. Callers seeding a store use it to delete the keys that a previous
     * configuration left behind (see {@link #isHidden(String)} and {@link #value(String)}).
     */
    public Set<String> managedKeys() {
        return Collections.unmodifiableSet(managed);
    }

    public boolean isHidden(String key) {
        return key != null && hidden.contains(key);
    }

    /** The override value for a managed key, or null when the key is hidden. */
    public Object value(String key) {
        return key == null ? null : values.get(key);
    }

    /** True when the adapter makes a value available (as opposed to hiding it). */
    public boolean contains(String key) {
        if (KEY_TARGET_PACKAGES.equals(key)) return true;
        return key != null && values.containsKey(key);
    }

    public Set<String> mergeTargetPackages(Set<String> base) {
        Set<String> merged = new LinkedHashSet<>();
        if (base != null) {
            for (String value : base) {
                if (value != null && !value.isEmpty()) merged.add(value);
            }
        }
        merged.add(host);
        return merged;
    }

    /**
     * Builds the effective map returned by {@code SharedPreferences.getAll()}.
     * The input map must not be modified.
     */
    public Map<String, Object> applyToMap(Map<String, ?> base) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (base != null) {
            for (Map.Entry<String, ?> entry : base.entrySet()) {
                if (entry.getKey() == null) continue;
                out.put(entry.getKey(), entry.getValue());
            }
        }
        for (String key : managed) {
            out.remove(key);
        }
        out.putAll(values);

        Set<String> targets = new LinkedHashSet<>();
        Object existing = out.get(KEY_TARGET_PACKAGES);
        if (existing instanceof Set) {
            for (Object item : (Set<?>) existing) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    targets.add((String) item);
                }
            }
        } else if (existing instanceof String) {
            targets.add((String) existing);
        }
        targets.add(host);
        out.put(KEY_TARGET_PACKAGES, Collections.unmodifiableSet(targets));
        return out;
    }

    private void valuePair(String domain, String field, Object value) {
        String legacy = domain + "." + host + "." + field;
        String aggregated = PACKAGE_PREFIX + host + "." + domain + "." + field;
        managed.add(legacy);
        managed.add(aggregated);
        values.put(legacy, value);
        values.put(aggregated, value);
    }

    private void hidePair(String domain, String field) {
        String legacy = domain + "." + host + "." + field;
        String aggregated = PACKAGE_PREFIX + host + "." + domain + "." + field;
        managed.add(legacy);
        managed.add(aggregated);
        hidden.add(legacy);
        hidden.add(aggregated);
    }
}
