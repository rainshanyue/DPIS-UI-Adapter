package com.dpis.uiadapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Optional allow-list for hosts. Absent or ["*"] means every patched host is handled. */
public final class TargetPolicy {

    private static final String ASSET = "assets/dpis_adapter_targets.json";
    private static volatile TargetPolicy instance;

    private final boolean all;
    private final Set<String> packages;

    private TargetPolicy(boolean all, Set<String> packages) {
        this.all = all;
        this.packages = packages;
    }

    public static TargetPolicy get() {
        TargetPolicy local = instance;
        if (local == null) {
            synchronized (TargetPolicy.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    public boolean matches(String packageName) {
        return all || (packageName != null && packages.contains(packageName));
    }

    private static TargetPolicy load() {
        JSONObject root = readAsset();
        if (root == null) {
            return new TargetPolicy(true, Collections.emptySet());
        }
        JSONArray array = root.optJSONArray("packages");
        if (array == null) {
            return new TargetPolicy(true, Collections.emptySet());
        }
        Set<String> packages = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (value.isEmpty()) continue;
            if ("*".equals(value)) {
                return new TargetPolicy(true, Collections.emptySet());
            }
            packages.add(value);
        }
        return new TargetPolicy(packages.isEmpty(), packages);
    }

    private static JSONObject readAsset() {
        try {
            ClassLoader loader = TargetPolicy.class.getClassLoader();
            InputStream in = loader != null ? loader.getResourceAsStream(ASSET) : null;
            if (in == null) in = ClassLoader.getSystemResourceAsStream(ASSET);
            if (in == null) return null;
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
