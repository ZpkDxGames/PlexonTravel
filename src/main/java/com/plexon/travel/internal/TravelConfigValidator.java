package com.plexon.travel.internal;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TravelConfigValidator {
    private TravelConfigValidator() {}

    public static List<String> validate(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        for (String type : List.of("spawn", "hub", "warp", "back", "admin")) {
            number(config, "teleport." + type + ".warmup-seconds", 0, 3600, errors);
            number(config, "teleport." + type + ".cooldown-seconds", 0, 86400, errors);
            String feePath = type.equals("warp") ? "teleport.warp.default-fee" : "teleport." + type + ".fee";
            number(config, feePath, 0, 1_000_000_000D, errors);
        }
        number(config, "teleport.movement-threshold", 0, 128, errors);
        number(config, "safe-teleport.horizontal-radius", 0, 8, errors);
        number(config, "safe-teleport.vertical-radius", 0, 12, errors);
        enumeration(config, "cooldowns.mode", Set.of("PER_TYPE", "GLOBAL"), errors);
        enumeration(config, "hub.mode", Set.of("SEPARATE", "SPAWN", "DISABLED"), errors);
        enumeration(config, "respawn.mode", Set.of("VANILLA", "SPAWN", "HUB", "LAST_BED_OR_SPAWN"), errors);
        return List.copyOf(errors);
    }

    private static void number(ConfigurationSection config, String path, double min, double max, List<String> errors) {
        Object raw = config.get(path);
        if (!(raw instanceof Number number)) { errors.add(path + " must be numeric"); return; }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) errors.add(path + " out of range [" + min + ", " + max + "]");
    }

    private static void enumeration(ConfigurationSection config, String path, Set<String> allowed, List<String> errors) {
        String value = config.getString(path, "").trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) errors.add(path + " must be one of " + allowed);
    }
}
