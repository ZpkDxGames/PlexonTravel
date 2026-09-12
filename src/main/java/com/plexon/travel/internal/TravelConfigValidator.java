package com.plexon.travel.internal;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TravelConfigValidator {
    private TravelConfigValidator() {}

    /**
     * Validates effective configuration values. Missing paths are intentionally accepted because
     * Bukkit applies the embedded config.yml defaults on startup/reload; this also keeps upgrades
     * from rejecting older, smaller config files before those defaults can be merged.
     *
     * <p>Important: do not use getString(path, fallback) after contains(path). Bukkit's
     * contains(path) returns true for inherited defaults, while getString(path, fallback) returns
     * the method fallback when the value is not explicitly present and ignores root defaults.
     * Reading the raw effective value with get(path) preserves inherited defaults.</p>
     */
    public static List<String> validate(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        for (String type : List.of("spawn", "hub", "warp", "back", "admin")) {
            number(config, "teleport." + type + ".warmup-seconds", 0, 3600, errors);
            number(config, "teleport." + type + ".cooldown-seconds", 0, 86400, errors);
            String feePath = type.equals("warp") ? "teleport.warp.default-fee" : "teleport." + type + ".fee";
            number(config, feePath, 0, 1_000_000_000D, errors);
        }
        for (String type : List.of("tpa", "rtp")) {
            optionalNumber(config, "teleport." + type + ".warmup-seconds", 0, 3600, errors);
            optionalNumber(config, "teleport." + type + ".cooldown-seconds", 0, 86400, errors);
            optionalNumber(config, "teleport." + type + ".fee", 0, 1_000_000_000D, errors);
        }
        number(config, "teleport.movement-threshold", 0, 128, errors);
        number(config, "safe-teleport.horizontal-radius", 0, 8, errors);
        number(config, "safe-teleport.vertical-radius", 0, 12, errors);
        optionalNumber(config, "effects.warmup.particle-count", 1, 40, errors);
        optionalNumber(config, "effects.complete.particle-count", 1, 80, errors);
        optionalNumber(config, "tpa.request-timeout-seconds", 5, 300, errors);
        optionalNumber(config, "rtp.min-radius", 0, 30_000_000, errors);
        optionalNumber(config, "rtp.max-radius", 1, 30_000_000, errors);
        optionalNumber(config, "rtp.max-attempts", 1, 64, errors);
        optionalNumber(config, "rtp.center.x", -30_000_000, 30_000_000, errors);
        optionalNumber(config, "rtp.center.z", -30_000_000, 30_000_000, errors);

        double minRadius = numericOr(config, "rtp.min-radius", 2000D);
        double maxRadius = numericOr(config, "rtp.max-radius", 10000D);
        if (Double.isFinite(minRadius) && Double.isFinite(maxRadius) && maxRadius <= minRadius) {
            errors.add("rtp.max-radius must be greater than rtp.min-radius");
        }

        enumeration(config, "cooldowns.mode", Set.of("PER_TYPE", "GLOBAL"), errors);
        enumeration(config, "hub.mode", Set.of("PER_WORLD", "GLOBAL", "SEPARATE", "SPAWN", "DISABLED"), errors);
        enumeration(config, "respawn.mode", Set.of("VANILLA", "SPAWN", "HUB", "LAST_BED_OR_SPAWN"), errors);
        optionalEnumeration(config, "destinations.spawn.fallback", Set.of("VANILLA", "NONE"), errors);
        optionalEnumeration(config, "destinations.hub.fallback", Set.of("GLOBAL", "SPAWN", "NONE"), errors);
        optionalEnumeration(config, "rtp.center.mode", Set.of("WORLD_SPAWN", "CONFIGURED"), errors);
        return List.copyOf(errors);
    }

    private static void number(ConfigurationSection config, String path, double min, double max, List<String> errors) {
        if (!config.contains(path)) return;
        Object raw = config.get(path);
        if (!(raw instanceof Number number)) {
            errors.add(path + " must be numeric");
            return;
        }
        range(path, number.doubleValue(), min, max, errors);
    }

    private static void optionalNumber(ConfigurationSection config, String path, double min, double max, List<String> errors) {
        number(config, path, min, max, errors);
    }

    private static void range(String path, double value, double min, double max, List<String> errors) {
        if (!Double.isFinite(value) || value < min || value > max) {
            errors.add(path + " out of range [" + min + ", " + max + "]");
        }
    }

    private static double numericOr(ConfigurationSection config, String path, double fallback) {
        Object raw = config.get(path);
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }

    private static void enumeration(ConfigurationSection config, String path, Set<String> allowed, List<String> errors) {
        if (!config.contains(path)) return;
        Object raw = config.get(path);
        if (!(raw instanceof String text)) {
            errors.add(path + " must be one of " + allowed + " (was " + describe(raw) + ")");
            return;
        }
        String value = text.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            errors.add(path + " must be one of " + allowed + " (was " + describe(text) + ")");
        }
    }

    private static void optionalEnumeration(ConfigurationSection config, String path, Set<String> allowed, List<String> errors) {
        enumeration(config, path, allowed, errors);
    }

    private static String describe(Object raw) {
        if (raw == null) return "<missing>";
        String value = String.valueOf(raw).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (value.length() > 48) value = value.substring(0, 48) + "...";
        return "'" + value + "'";
    }
}
