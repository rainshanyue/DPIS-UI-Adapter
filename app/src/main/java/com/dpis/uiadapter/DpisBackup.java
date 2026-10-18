package com.dpis.uiadapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

/**
 * Writes the same JSON document shape as DPIS's own "export configuration" action
 * (ConfigBackupCodec, schemaVersion 4).
 *
 * <p>The original app validates packageName, metadata and the key namespaces on
 * import, so all of them are reproduced here rather than inventing a private
 * backup format.</p>
 */
public final class DpisBackup {

    private DpisBackup() {
    }

    public static String build(String host, AdapterConfig.PackageConfig config, DpisMeta meta)
            throws JSONException {
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("host is empty");
        AdapterConfig.PackageConfig pkg = config != null ? config : new AdapterConfig.PackageConfig();
        DpisMeta dpis = meta != null ? meta : DpisMeta.defaults();

        JSONObject root = new JSONObject();
        root.put("schemaVersion", 4);
        root.put("createdAtEpochMs", System.currentTimeMillis());
        root.put("packageName", dpis.packageName);
        // Both keys are mandatory: DPIS's ConfigBackupCodec.decode() rejects a document
        // that lacks them ("Missing backup metadata"), but it never compares the values.
        // They are 0 / "" here because this module no longer pins a DPIS version.
        root.put("appVersionCode", dpis.versionCode);
        root.put("appVersionName", dpis.versionName);

        JSONObject viewport = new JSONObject();
        if (pkg.isRelativeMode()) {
            viewport.put("target_type", "relative_scale");
            viewport.put("scale_milli_percent", pkg.scalePercent * 1000);
            // Kept for older DPIS readers that still resolve the permille key first.
            viewport.put("scale_permille", pkg.scalePercent * 10);
        } else {
            viewport.put("target_type", "absolute_dp");
            viewport.put("width_dp", pkg.widthDp);
        }

        JSONObject packageEntry = new JSONObject();
        packageEntry.put("viewport", viewport);
        if (pkg.fontScalePercent != 100) {
            JSONObject font = new JSONObject();
            font.put("scale_percent", pkg.fontScalePercent);
            packageEntry.put("font", font);
        }
        if (!pkg.dpisEnabled) {
            JSONObject target = new JSONObject();
            target.put("dpis_enabled", false);
            packageEntry.put("target", target);
        }

        JSONObject packageConfigs = new JSONObject();
        packageConfigs.put(host, packageEntry);
        root.put("packageConfigs", packageConfigs);

        JSONObject global = new JSONObject();
        global.put("target_packages", new JSONArray(Collections.singletonList(host)));
        global.put("log_enabled", pkg.logEnabled);
        root.put("global", global);

        root.put("moduleScope", new JSONArray(Collections.singletonList(host)));
        return root.toString(2);
    }
}
