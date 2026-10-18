package com.dpis.uiadapter;

import org.json.JSONObject;

/** Metadata used when writing a backup that the original DPIS GUI can restore. */
public final class DpisMeta {
    public static final String DEFAULT_PACKAGE = "io.github.kwensiu.dpis";

    public final String packageName;

    /** Empty when unknown: neither the asset nor this module pins a DPIS version. */
    public final String versionName;

    /** 0 when unknown. DPIS's importer requires the key to exist, not to match. */
    public final int versionCode;

    public DpisMeta(String packageName, String versionName, int versionCode) {
        this.packageName = packageName == null || packageName.isBlank()
                ? DEFAULT_PACKAGE : packageName;
        this.versionName = versionName == null ? "" : versionName.trim();
        this.versionCode = Math.max(0, versionCode);
    }

    public static DpisMeta defaults() {
        return new DpisMeta(DEFAULT_PACKAGE, "", 0);
    }

    public static DpisMeta from(JSONObject object) {
        if (object == null) return defaults();
        return new DpisMeta(
                object.optString("package_name", DEFAULT_PACKAGE),
                object.optString("version_name", ""),
                object.optInt("version_code", 0));
    }
}
