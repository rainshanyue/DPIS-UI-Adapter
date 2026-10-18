package com.dpis.uiadapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdapterConfigTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File configFile() {
        return new File(temporaryFolder.getRoot(), "dpis_ui_adapter/config.json");
    }

    @Test
    public void defaultsAreNeutralForUnknownHost() {
        AdapterConfig config = AdapterConfig.load(configFile());
        AdapterConfig.PackageConfig pkg = config.getPackage("com.example.host");
        assertEquals(100, pkg.scalePercent);
        assertEquals(360, pkg.widthDp);
        assertEquals(100, pkg.fontScalePercent);
        assertEquals(AdapterConfig.MODE_RELATIVE, pkg.viewportMode);
        assertTrue(pkg.dpisEnabled);
        assertTrue(pkg.enabled);
        assertFalse(config.shouldOverlay("com.example.host"));
        assertFalse(config.fileExists());
    }

    @Test
    public void bundledAssetShipsNoPerPackageDefault() {
        AdapterConfig config = AdapterConfig.load(configFile());
        AdapterConfig.PackageConfig pkg = config.getPackage("com.example.host");
        assertEquals(100, pkg.scalePercent);
        // Generic build: nothing is opted in until the user saves a package.
        assertFalse(config.hasAssetDefault("com.example.host"));
        assertFalse(config.shouldOverlay("com.example.host"));
    }

    @Test
    public void savedPackageSurvivesReload() {
        File file = configFile();
        AdapterConfig config = AdapterConfig.load(file);
        AdapterConfig.PackageConfig pkg = config.getPackage("com.example.host");
        pkg.scalePercent = 65;
        pkg.fontScalePercent = 90;
        pkg.logEnabled = true;
        pkg.viewportMode = AdapterConfig.MODE_ABSOLUTE;
        pkg.widthDp = 420;
        config.savePackage("com.example.host", pkg);
        assertTrue(config.fileExists());

        AdapterConfig reloaded = AdapterConfig.load(file);
        AdapterConfig.PackageConfig loaded = reloaded.getPackage("com.example.host");
        assertEquals(65, loaded.scalePercent);
        assertEquals(90, loaded.fontScalePercent);
        assertEquals(420, loaded.widthDp);
        assertEquals(AdapterConfig.MODE_ABSOLUTE, loaded.viewportMode);
        assertTrue(loaded.logEnabled);
        assertTrue(loaded.dpisEnabled);
        assertTrue(loaded.explicit);
        assertTrue(reloaded.hasExplicitPackage("com.example.host"));
    }

    @Test
    public void packagesAreIsolated() {
        File file = configFile();
        AdapterConfig config = AdapterConfig.load(file);
        AdapterConfig.PackageConfig first = config.getPackage("com.example.one");
        first.scalePercent = 70;
        config.savePackage("com.example.one", first);
        AdapterConfig.PackageConfig second = config.getPackage("com.example.two");
        second.scalePercent = 120;
        config.savePackage("com.example.two", second);

        AdapterConfig reloaded = AdapterConfig.load(file);
        assertEquals(70, reloaded.getPackage("com.example.one").scalePercent);
        assertEquals(120, reloaded.getPackage("com.example.two").scalePercent);
        assertEquals(100, reloaded.getPackage("com.example.three").scalePercent);
    }

    @Test
    public void uiStateDoesNotEnableTheOverlay() {
        File file = configFile();
        AdapterConfig config = AdapterConfig.load(file);
        config.saveUi("com.example.host", 321, 654, false);
        assertTrue(config.fileExists());

        AdapterConfig reloaded = AdapterConfig.load(file);
        AdapterConfig.PackageConfig pkg = reloaded.getPackage("com.example.host");
        assertEquals(321, pkg.x);
        assertEquals(654, pkg.y);
        assertFalse(pkg.enabled);
        assertFalse(reloaded.shouldOverlay("com.example.host"));
        assertFalse(reloaded.hasExplicitPackage("com.example.host"));
    }

    @Test
    public void resetRemovesExplicitPackageButKeepsUiState() {
        File file = configFile();
        AdapterConfig config = AdapterConfig.load(file);
        AdapterConfig.PackageConfig pkg = config.getPackage("com.example.host");
        pkg.scalePercent = 75;
        config.savePackage("com.example.host", pkg);
        config.saveUi("com.example.host", 12, 34, true);

        config.resetPackage("com.example.host");
        AdapterConfig reloaded = AdapterConfig.load(file);
        assertFalse(reloaded.hasExplicitPackage("com.example.host"));
        assertFalse(reloaded.shouldOverlay("com.example.host"));
        assertEquals(12, reloaded.getPackage("com.example.host").x);
        assertEquals(34, reloaded.getPackage("com.example.host").y);
        assertTrue(reloaded.getPackage("com.example.host").enabled);
    }

    @Test
    public void saveClampsOutOfRangeValues() {
        File file = configFile();
        AdapterConfig config = AdapterConfig.load(file);
        AdapterConfig.PackageConfig pkg = new AdapterConfig.PackageConfig();
        pkg.scalePercent = 1;
        pkg.widthDp = 99999;
        pkg.fontScalePercent = 1;
        config.savePackage("com.example.host", pkg);

        AdapterConfig reloaded = AdapterConfig.load(file);
        AdapterConfig.PackageConfig loaded = reloaded.getPackage("com.example.host");
        assertEquals(AdapterConfig.MIN_SCALE_PERCENT, loaded.scalePercent);
        assertEquals(AdapterConfig.MAX_WIDTH_DP, loaded.widthDp);
        assertEquals(AdapterConfig.MIN_FONT_PERCENT, loaded.fontScalePercent);
    }
}
