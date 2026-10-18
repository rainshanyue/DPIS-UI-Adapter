package com.dpis.uiadapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OverlayValuesTest {

    private static AdapterConfig.PackageConfig relative() {
        AdapterConfig.PackageConfig config = new AdapterConfig.PackageConfig();
        config.viewportMode = AdapterConfig.MODE_RELATIVE;
        config.scalePercent = 80;
        config.fontScalePercent = 100;
        return config;
    }

    @Test
    public void relativeModeMapsDpisKeysAndHidesWidth() {
        OverlayValues values = new OverlayValues("com.example.host", relative(), false);
        String prefix = "package_config.com.example.host.";
        assertTrue(values.isManaged(prefix + "viewport.target_type"));
        assertEquals("relative_scale", values.value(prefix + "viewport.target_type"));
        assertEquals(80000, values.value(prefix + "viewport.scale_milli_percent"));
        assertEquals(800, values.value(prefix + "viewport.scale_permille"));
        assertTrue(values.isHidden(prefix + "viewport.width_dp"));
        assertFalse(values.contains(prefix + "viewport.width_dp"));
        // The historical namespace must be overridden as well.
        assertTrue(values.isManaged("viewport.com.example.host.target_type"));
        assertEquals("relative_scale",
                values.value("viewport.com.example.host.target_type"));
        assertEquals(800, values.value("viewport.com.example.host.scale_permille"));
        assertTrue(values.isHidden("viewport.com.example.host.width_dp"));
        assertTrue(values.isHidden("viewport.com.example.host.mode"));
        assertTrue(values.isHidden("package_config.com.example.host.viewport.mode"));
        assertTrue(values.isHidden(prefix + "font.scale_percent"));
        assertTrue(values.isHidden(prefix + "target.dpis_enabled"));
        assertFalse(values.isManaged("package_config.other.viewport.width_dp"));
        assertFalse(values.isManaged("global.log_enabled"));
    }

    @Test
    public void absoluteModeHidesScaleKeys() {
        AdapterConfig.PackageConfig config = new AdapterConfig.PackageConfig();
        config.viewportMode = AdapterConfig.MODE_ABSOLUTE;
        config.widthDp = 420;
        OverlayValues values = new OverlayValues("com.example.host", config, false);
        String prefix = "package_config.com.example.host.";
        assertEquals("absolute_dp", values.value(prefix + "viewport.target_type"));
        assertEquals(420, values.value(prefix + "viewport.width_dp"));
        assertTrue(values.isHidden(prefix + "viewport.scale_milli_percent"));
        assertTrue(values.isHidden(prefix + "viewport.scale_permille"));
    }

    @Test
    public void disabledAndFontAndGlobalAreOverridden() {
        AdapterConfig.PackageConfig config = relative();
        config.dpisEnabled = false;
        config.fontScalePercent = 90;
        config.logEnabled = true;
        OverlayValues values = new OverlayValues("com.example.host", config, true);
        String prefix = "package_config.com.example.host.";
        assertEquals(Boolean.FALSE, values.value(prefix + "target.dpis_enabled"));
        assertTrue(values.contains(prefix + "target.dpis_enabled"));
        assertEquals(90, values.value(prefix + "font.scale_percent"));
        assertEquals(Boolean.TRUE, values.value("global.log_enabled"));
        assertTrue(values.contains("global.log_enabled"));
    }

    @Test
    public void applyToMapPreservesForeignKeysAndMergesTargetPackages() {
        AdapterConfig.PackageConfig config = relative();
        OverlayValues values = new OverlayValues("com.example.host", config, false);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("package_config.com.other.viewport.scale_milli_percent", 110000);
        base.put("global.log_enabled", true);
        base.put("target_packages", new LinkedHashSet<>(Arrays.asList(
                "com.other", "com.example.host")));
        base.put("package_config.com.example.host.viewport.width_dp", 360);
        base.put("viewport.com.example.host.width_dp", 360);
        base.put("viewport.com.example.host.mode", "off");

        Map<String, Object> merged = values.applyToMap(base);
        assertEquals(110000, merged.get(
                "package_config.com.other.viewport.scale_milli_percent"));
        assertEquals(Boolean.TRUE, merged.get("global.log_enabled"));
        assertNull(merged.get("package_config.com.example.host.viewport.width_dp"));
        assertNull(merged.get("viewport.com.example.host.width_dp"));
        assertNull(merged.get("viewport.com.example.host.mode"));
        assertEquals(80000, merged.get(
                "package_config.com.example.host.viewport.scale_milli_percent"));
        assertEquals(800, merged.get("viewport.com.example.host.scale_permille"));
        Set<?> targets = (Set<?>) merged.get("target_packages");
        assertTrue(targets.contains("com.other"));
        assertTrue(targets.contains("com.example.host"));
    }

    @Test
    public void fontModeCompatWritesFieldRewrite() {
        AdapterConfig.PackageConfig config = relative();
        config.fontScalePercent = 120;
        config.fontMode = AdapterConfig.FONT_MODE_COMPAT;
        OverlayValues values = new OverlayValues("com.example.host", config, false);
        assertFalse(values.isHidden("font.com.example.host.mode"));
        assertEquals("field_rewrite", values.value("font.com.example.host.mode"));
        assertEquals("field_rewrite",
                values.value("package_config.com.example.host.font.mode"));
    }

    @Test
    public void fontModeSystemWritesSystemEmulation() {
        AdapterConfig.PackageConfig config = relative();
        config.fontScalePercent = 120;
        config.fontMode = AdapterConfig.FONT_MODE_SYSTEM;
        OverlayValues values = new OverlayValues("com.example.host", config, false);
        assertEquals("system_emulation", values.value("font.com.example.host.mode"));
    }

    @Test
    public void fontModeAutoLeavesTheKeyAbsent() {
        AdapterConfig.PackageConfig config = relative();
        config.fontScalePercent = 120;
        config.fontMode = AdapterConfig.FONT_MODE_AUTO;
        OverlayValues values = new OverlayValues("com.example.host", config, false);
        assertTrue(values.isHidden("font.com.example.host.mode"));
        assertNull(values.value("font.com.example.host.mode"));
    }

    @Test
    public void targetPackagesUnionHandlesNull() {
        OverlayValues values = new OverlayValues("com.example.host", relative(), false);
        Set<String> merged = values.mergeTargetPackages(null);
        assertEquals(1, merged.size());
        assertTrue(merged.contains("com.example.host"));
    }
}
