package com.dpis.uiadapter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DpisBackupTest {

    @Test
    public void backupMatchesDpisSchemaFourShape() throws Exception {
        AdapterConfig.PackageConfig config = new AdapterConfig.PackageConfig();
        config.viewportMode = AdapterConfig.MODE_RELATIVE;
        config.scalePercent = 80;
        config.fontScalePercent = 100;
        config.logEnabled = false;
        config.dpisEnabled = true;

        JSONObject root = new JSONObject(DpisBackup.build("com.example.host", config,
                new DpisMeta("io.github.kwensiu.dpis", "2.3.0", 20300)));

        assertEquals(4, root.getInt("schemaVersion"));
        assertEquals("io.github.kwensiu.dpis", root.getString("packageName"));
        assertEquals(20300, root.getInt("appVersionCode"));
        assertEquals("2.3.0", root.getString("appVersionName"));
        assertTrue(root.has("createdAtEpochMs"));

        JSONObject bili = root.getJSONObject("packageConfigs")
                .getJSONObject("com.example.host");
        JSONObject viewport = bili.getJSONObject("viewport");
        assertEquals("relative_scale", viewport.getString("target_type"));
        assertEquals(80000, viewport.getInt("scale_milli_percent"));
        assertEquals(800, viewport.getInt("scale_permille"));
        assertFalse(viewport.has("width_dp"));
        assertFalse(bili.has("font"));
        assertFalse(bili.has("target"));

        JSONObject global = root.getJSONObject("global");
        JSONArray targets = global.getJSONArray("target_packages");
        assertEquals(1, targets.length());
        assertEquals("com.example.host", targets.getString(0));
        assertFalse(global.getBoolean("log_enabled"));

        JSONArray scope = root.getJSONArray("moduleScope");
        assertEquals("com.example.host", scope.getString(0));
    }

    @Test
    public void unknownVersionStillEmitsMandatoryMetadataKeys() throws Exception {
        AdapterConfig.PackageConfig config = new AdapterConfig.PackageConfig();
        JSONObject root = new JSONObject(DpisBackup.build("com.example.host", config,
                DpisMeta.defaults()));
        // DPIS rejects a backup without these two keys, so they must exist even when the
        // version is unknown (this module no longer pins one).
        assertTrue(root.has("appVersionCode"));
        assertTrue(root.has("appVersionName"));
        assertEquals(0, root.getInt("appVersionCode"));
        assertEquals("", root.getString("appVersionName"));
        assertEquals("io.github.kwensiu.dpis", root.getString("packageName"));
    }

    @Test
    public void absoluteAndFontAndDisabledAreRepresented() throws Exception {
        AdapterConfig.PackageConfig config = new AdapterConfig.PackageConfig();
        config.viewportMode = AdapterConfig.MODE_ABSOLUTE;
        config.widthDp = 420;
        config.fontScalePercent = 90;
        config.logEnabled = true;
        config.dpisEnabled = false;

        JSONObject root = new JSONObject(DpisBackup.build("com.example.host", config,
                DpisMeta.defaults()));
        JSONObject pkg = root.getJSONObject("packageConfigs")
                .getJSONObject("com.example.host");
        JSONObject viewport = pkg.getJSONObject("viewport");
        assertEquals("absolute_dp", viewport.getString("target_type"));
        assertEquals(420, viewport.getInt("width_dp"));
        assertFalse(viewport.has("scale_milli_percent"));
        assertEquals(90, pkg.getJSONObject("font").getInt("scale_percent"));
        assertFalse(pkg.getJSONObject("target").getBoolean("dpis_enabled"));
        assertTrue(root.getJSONObject("global").getBoolean("log_enabled"));
    }
}
