package com.plexon.travel;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

final class WorldSettingsManager {
    static final int SCHEMA_VERSION = 1;
    static final double COORDINATE_LIMIT = 30_000_000D;
    static final int MAX_RTP_ATTEMPTS = 64;

    private final PlexonTravel plugin;
    private final Path file;
    private volatile Map<UUID, WorldTravelRule> explicitRules = Map.of();
    private volatile LegacyRtpDefaults legacyDefaults;

    WorldSettingsManager(PlexonTravel plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("world-settings.yml");
        this.legacyDefaults = LegacyRtpDefaults.from(plugin);
    }

    void load() throws IOException {
        legacyDefaults = LegacyRtpDefaults.from(plugin);
        if (!Files.exists(file)) saveSnapshot(Map.of());
        ParseResult parsed = parse(YamlConfiguration.loadConfiguration(file.toFile()), legacyDefaults);
        if (!parsed.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", parsed.errors()));
        explicitRules = parsed.rules();
    }

    List<String> validateDisk() {
        if (!Files.exists(file)) return List.of();
        return parse(YamlConfiguration.loadConfiguration(file.toFile()), LegacyRtpDefaults.from(plugin)).errors();
    }

    void reloadFromDisk() throws IOException {
        LegacyRtpDefaults candidateLegacy = LegacyRtpDefaults.from(plugin);
        if (!Files.exists(file)) saveSnapshot(Map.of());
        ParseResult parsed = parse(YamlConfiguration.loadConfiguration(file.toFile()), candidateLegacy);
        if (!parsed.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", parsed.errors()));
        legacyDefaults = candidateLegacy;
        explicitRules = parsed.rules();
    }

    void refreshLegacyDefaults() {
        legacyDefaults = LegacyRtpDefaults.from(plugin);
    }

    RtpProfile rtpProfile(World world) {
        WorldTravelRule rule = explicitRules.get(world.getUID());
        if (rule != null && rule.rtp() != null) return rule.rtp();
        return legacyDefaults.profileFor(world);
    }

    VoidRescueRule voidRule(World world) {
        WorldTravelRule rule = explicitRules.get(world.getUID());
        if (rule != null && rule.voidRescue() != null) return rule.voidRescue();
        return defaultVoidRule(world);
    }

    boolean hasExplicitRtp(UUID worldId) {
        WorldTravelRule rule = explicitRules.get(worldId);
        return rule != null && rule.rtp() != null;
    }

    boolean hasExplicitVoid(UUID worldId) {
        WorldTravelRule rule = explicitRules.get(worldId);
        return rule != null && rule.voidRescue() != null;
    }

    int explicitRtpCount() {
        return (int) explicitRules.values().stream().filter(rule -> rule.rtp() != null).count();
    }

    int explicitVoidCount() {
        return (int) explicitRules.values().stream().filter(rule -> rule.voidRescue() != null).count();
    }

    int enabledVoidWorldCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) if (voidRule(world).enabled()) count++;
        return count;
    }

    int legacyInheritedLoadedWorldCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) if (!hasExplicitRtp(world.getUID())) count++;
        return count;
    }

    synchronized RtpProfile mutateRtp(World world, UnaryOperator<RtpProfile> mutation) throws IOException {
        RtpProfile base = rtpProfile(world).explicit();
        RtpProfile changed = mutation.apply(base);
        List<String> errors = validateRtp(changed, "worlds." + world.getUID() + ".rtp");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        Map<UUID, WorldTravelRule> candidate = new HashMap<>(explicitRules);
        WorldTravelRule current = candidate.getOrDefault(world.getUID(), new WorldTravelRule(world.getName(), null, null));
        candidate.put(world.getUID(), new WorldTravelRule(world.getName(), changed.explicit(), current.voidRescue()));
        activate(candidate);
        return changed.explicit();
    }

    synchronized void resetRtp(World world) throws IOException {
        Map<UUID, WorldTravelRule> candidate = new HashMap<>(explicitRules);
        WorldTravelRule current = candidate.get(world.getUID());
        if (current == null) return;
        WorldTravelRule changed = new WorldTravelRule(world.getName(), null, current.voidRescue());
        if (changed.empty()) candidate.remove(world.getUID());
        else candidate.put(world.getUID(), changed);
        activate(candidate);
    }

    synchronized VoidRescueRule mutateVoid(World world, UnaryOperator<VoidRescueRule> mutation) throws IOException {
        VoidRescueRule base = hasExplicitVoid(world.getUID()) ? explicitRules.get(world.getUID()).voidRescue() : defaultVoidRule(world);
        VoidRescueRule changed = mutation.apply(base);
        List<String> errors = validateVoid(changed, "worlds." + world.getUID() + ".void-rescue");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        Map<UUID, WorldTravelRule> candidate = new HashMap<>(explicitRules);
        WorldTravelRule current = candidate.getOrDefault(world.getUID(), new WorldTravelRule(world.getName(), null, null));
        candidate.put(world.getUID(), new WorldTravelRule(world.getName(), current.rtp(), changed));
        activate(candidate);
        return changed;
    }

    synchronized void resetVoid(World world) throws IOException {
        Map<UUID, WorldTravelRule> candidate = new HashMap<>(explicitRules);
        WorldTravelRule current = candidate.get(world.getUID());
        if (current == null) return;
        WorldTravelRule changed = new WorldTravelRule(world.getName(), current.rtp(), null);
        if (changed.empty()) candidate.remove(world.getUID());
        else candidate.put(world.getUID(), changed);
        activate(candidate);
    }

    private void activate(Map<UUID, WorldTravelRule> candidate) throws IOException {
        Map<UUID, WorldTravelRule> immutable = Map.copyOf(candidate);
        saveSnapshot(immutable);
        explicitRules = immutable;
    }

    private void saveSnapshot(Map<UUID, WorldTravelRule> rules) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);
        for (Map.Entry<UUID, WorldTravelRule> entry : rules.entrySet()) {
            String base = "worlds." + entry.getKey();
            WorldTravelRule rule = entry.getValue();
            yaml.set(base + ".name", rule.lastKnownName());
            if (rule.rtp() != null) writeRtp(yaml, base + ".rtp", rule.rtp());
            if (rule.voidRescue() != null) writeVoid(yaml, base + ".void-rescue", rule.voidRescue());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        yaml.save(temp.toFile());
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeRtp(YamlConfiguration yaml, String base, RtpProfile profile) {
        yaml.set(base + ".enabled", profile.enabled());
        yaml.set(base + ".boundary.mode", profile.boundaryMode().name());
        yaml.set(base + ".boundary.annulus.center.mode", profile.centerMode().name());
        yaml.set(base + ".boundary.annulus.center.x", profile.centerX());
        yaml.set(base + ".boundary.annulus.center.z", profile.centerZ());
        yaml.set(base + ".boundary.annulus.min-radius", profile.minRadius());
        yaml.set(base + ".boundary.annulus.max-radius", profile.maxRadius());
        yaml.set(base + ".boundary.rectangle.min-x", profile.minX());
        yaml.set(base + ".boundary.rectangle.max-x", profile.maxX());
        yaml.set(base + ".boundary.rectangle.min-z", profile.minZ());
        yaml.set(base + ".boundary.rectangle.max-z", profile.maxZ());
        yaml.set(base + ".boundary.world-border.padding", profile.worldBorderPadding());
        yaml.set(base + ".max-attempts", profile.maxAttempts());
        yaml.set(base + ".generate-chunks", profile.generateChunks());
    }

    private static void writeVoid(YamlConfiguration yaml, String base, VoidRescueRule rule) {
        yaml.set(base + ".enabled", rule.enabled());
        yaml.set(base + ".trigger-y", rule.triggerY());
        yaml.set(base + ".rescue-creative", rule.rescueCreative());
        VoidDestination destination = rule.destination();
        yaml.set(base + ".destination.mode", destination.mode().name());
        yaml.set(base + ".destination.target-world", destination.targetWorld());
        yaml.set(base + ".destination.x", destination.x());
        yaml.set(base + ".destination.y", destination.y());
        yaml.set(base + ".destination.z", destination.z());
        yaml.set(base + ".destination.yaw", destination.yaw());
        yaml.set(base + ".destination.pitch", destination.pitch());
    }

    static ParseResult parse(ConfigurationSection root, LegacyRtpDefaults legacy) {
        List<String> errors = new ArrayList<>();
        int schema = root.getInt("schema-version", SCHEMA_VERSION);
        if (schema != SCHEMA_VERSION) errors.add("world-settings schema-version must be " + SCHEMA_VERSION);
        Map<UUID, WorldTravelRule> rules = new HashMap<>();
        ConfigurationSection worlds = root.getConfigurationSection("worlds");
        if (worlds == null) return new ParseResult(Map.of(), List.copyOf(errors));
        for (String key : worlds.getKeys(false)) {
            UUID worldId;
            try {
                worldId = UUID.fromString(key);
            } catch (IllegalArgumentException invalid) {
                errors.add("worlds." + key + " is not a valid world UUID");
                continue;
            }
            ConfigurationSection section = worlds.getConfigurationSection(key);
            if (section == null) continue;
            String name = section.getString("name", key).trim();
            if (name.isBlank()) name = key;
            RtpProfile rtp = null;
            ConfigurationSection rtpSection = section.getConfigurationSection("rtp");
            if (rtpSection != null) {
                rtp = parseRtp(rtpSection, legacy);
                errors.addAll(validateRtp(rtp, "worlds." + key + ".rtp"));
            }
            VoidRescueRule voidRule = null;
            ConfigurationSection voidSection = section.getConfigurationSection("void-rescue");
            if (voidSection != null) {
                double defaultTrigger = -60D;
                World loaded = Bukkit.getWorld(worldId);
                if (loaded != null) defaultTrigger = loaded.getMinHeight() + 4D;
                voidRule = parseVoid(voidSection, defaultTrigger);
                errors.addAll(validateVoid(voidRule, "worlds." + key + ".void-rescue"));
            }
            rules.put(worldId, new WorldTravelRule(name, rtp, voidRule));
        }
        return new ParseResult(Map.copyOf(rules), List.copyOf(errors));
    }

    private static RtpProfile parseRtp(ConfigurationSection section, LegacyRtpDefaults legacy) {
        RtpBoundaryMode mode = enumValue(section.getString("boundary.mode", "ANNULUS"), RtpBoundaryMode.class, RtpBoundaryMode.ANNULUS);
        RtpCenterMode centerMode = enumValue(section.getString("boundary.annulus.center.mode", legacy.centerMode().name()), RtpCenterMode.class, legacy.centerMode());
        return new RtpProfile(
            section.getBoolean("enabled", legacy.enabled()),
            mode,
            centerMode,
            section.getDouble("boundary.annulus.center.x", legacy.centerX()),
            section.getDouble("boundary.annulus.center.z", legacy.centerZ()),
            section.getDouble("boundary.annulus.min-radius", legacy.minRadius()),
            section.getDouble("boundary.annulus.max-radius", legacy.maxRadius()),
            section.getDouble("boundary.rectangle.min-x", -10_000D),
            section.getDouble("boundary.rectangle.max-x", 10_000D),
            section.getDouble("boundary.rectangle.min-z", -10_000D),
            section.getDouble("boundary.rectangle.max-z", 10_000D),
            section.getDouble("boundary.world-border.padding", 16D),
            section.getInt("max-attempts", legacy.maxAttempts()),
            section.getBoolean("generate-chunks", legacy.generateChunks()),
            RtpProfileSource.EXPLICIT
        );
    }

    private static VoidRescueRule parseVoid(ConfigurationSection section, double defaultTrigger) {
        VoidDestinationMode mode = enumValue(section.getString("destination.mode", "PLEXON_SPAWN"), VoidDestinationMode.class, VoidDestinationMode.PLEXON_SPAWN);
        VoidDestination destination = new VoidDestination(
            mode,
            section.getString("destination.target-world", "").trim(),
            section.getDouble("destination.x", 0.5D),
            section.getDouble("destination.y", 100D),
            section.getDouble("destination.z", 0.5D),
            (float) section.getDouble("destination.yaw", 0D),
            (float) section.getDouble("destination.pitch", 0D)
        );
        return new VoidRescueRule(section.getBoolean("enabled", false), section.getDouble("trigger-y", defaultTrigger),
            destination, section.getBoolean("rescue-creative", false));
    }

    static List<String> validateRtp(RtpProfile profile, String base) {
        List<String> errors = new ArrayList<>();
        if (profile == null) return errors;
        if (profile.boundaryMode() == null) errors.add(base + ".boundary.mode is invalid");
        if (profile.centerMode() == null) errors.add(base + ".boundary.annulus.center.mode is invalid");
        finiteRange(base + ".boundary.annulus.center.x", profile.centerX(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
        finiteRange(base + ".boundary.annulus.center.z", profile.centerZ(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
        if (profile.boundaryMode() == RtpBoundaryMode.ANNULUS) {
            finiteRange(base + ".boundary.annulus.min-radius", profile.minRadius(), 0D, COORDINATE_LIMIT, errors);
            finiteRange(base + ".boundary.annulus.max-radius", profile.maxRadius(), 0D, COORDINATE_LIMIT, errors);
            if (Double.isFinite(profile.minRadius()) && Double.isFinite(profile.maxRadius()) && profile.maxRadius() <= profile.minRadius()) {
                errors.add(base + ".boundary.annulus.max-radius must be greater than min-radius");
            }
        }
        if (profile.boundaryMode() == RtpBoundaryMode.RECTANGLE) {
            finiteRange(base + ".boundary.rectangle.min-x", profile.minX(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            finiteRange(base + ".boundary.rectangle.max-x", profile.maxX(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            finiteRange(base + ".boundary.rectangle.min-z", profile.minZ(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            finiteRange(base + ".boundary.rectangle.max-z", profile.maxZ(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            if (profile.maxX() <= profile.minX()) errors.add(base + ".boundary.rectangle.max-x must be greater than min-x");
            if (profile.maxZ() <= profile.minZ()) errors.add(base + ".boundary.rectangle.max-z must be greater than min-z");
        }
        finiteRange(base + ".boundary.world-border.padding", profile.worldBorderPadding(), 0D, COORDINATE_LIMIT, errors);
        if (profile.maxAttempts() < 1 || profile.maxAttempts() > MAX_RTP_ATTEMPTS) {
            errors.add(base + ".max-attempts out of range [1, " + MAX_RTP_ATTEMPTS + "]");
        }
        return errors;
    }

    static List<String> validateVoid(VoidRescueRule rule, String base) {
        List<String> errors = new ArrayList<>();
        if (rule == null) return errors;
        finiteRange(base + ".trigger-y", rule.triggerY(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
        if (rule.destination() == null || rule.destination().mode() == null) {
            errors.add(base + ".destination.mode is invalid");
            return errors;
        }
        VoidDestination destination = rule.destination();
        if (destination.mode() == VoidDestinationMode.FIXED) {
            finiteRange(base + ".destination.x", destination.x(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            finiteRange(base + ".destination.y", destination.y(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            finiteRange(base + ".destination.z", destination.z(), -COORDINATE_LIMIT, COORDINATE_LIMIT, errors);
            if (!Float.isFinite(destination.yaw())) errors.add(base + ".destination.yaw must be finite");
            if (!Float.isFinite(destination.pitch())) errors.add(base + ".destination.pitch must be finite");
        }
        return errors;
    }

    private static void finiteRange(String path, double value, double min, double max, List<String> errors) {
        if (!Double.isFinite(value) || value < min || value > max) errors.add(path + " out of range [" + min + ", " + max + "]");
    }

    private static <E extends Enum<E>> E enumValue(String raw, Class<E> type, E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static VoidRescueRule defaultVoidRule(World world) {
        return new VoidRescueRule(false, world.getMinHeight() + 4D, VoidDestination.plexonSpawn(), false);
    }

    record ParseResult(Map<UUID, WorldTravelRule> rules, List<String> errors) {}

    record LegacyRtpDefaults(
        boolean enabled,
        Set<String> allowedWorlds,
        RtpCenterMode centerMode,
        double centerX,
        double centerZ,
        double minRadius,
        double maxRadius,
        int maxAttempts,
        boolean generateChunks
    ) {
        static LegacyRtpDefaults from(PlexonTravel plugin) {
            List<String> configured = plugin.getConfig().getStringList("rtp.allowed-worlds");
            if (configured.isEmpty()) configured = List.of("Survival_World");
            Set<String> allowed = new HashSet<>();
            for (String value : configured) allowed.add(value.toLowerCase(Locale.ROOT));
            RtpCenterMode center = enumValue(plugin.getConfig().getString("rtp.center.mode", "WORLD_SPAWN"), RtpCenterMode.class, RtpCenterMode.WORLD_SPAWN);
            if (center == null) center = RtpCenterMode.WORLD_SPAWN;
            return new LegacyRtpDefaults(
                plugin.getConfig().getBoolean("rtp.enabled", true), Set.copyOf(allowed), center,
                plugin.getConfig().getDouble("rtp.center.x", 0D), plugin.getConfig().getDouble("rtp.center.z", 0D),
                plugin.getConfig().getDouble("rtp.min-radius", 2000D), plugin.getConfig().getDouble("rtp.max-radius", 10000D),
                Math.max(1, Math.min(MAX_RTP_ATTEMPTS, plugin.getConfig().getInt("rtp.max-attempts", 24))),
                plugin.getConfig().getBoolean("rtp.generate-chunks", false));
        }

        static LegacyRtpDefaults testDefaults() {
            return new LegacyRtpDefaults(true, Set.of("survival_world"), RtpCenterMode.WORLD_SPAWN,
                0D, 0D, 2000D, 10000D, 24, false);
        }

        RtpProfile profileFor(World world) {
            boolean worldEnabled = enabled && allowedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
            return new RtpProfile(worldEnabled, RtpBoundaryMode.ANNULUS, centerMode, centerX, centerZ,
                minRadius, maxRadius, -10_000D, 10_000D, -10_000D, 10_000D, 16D,
                maxAttempts, generateChunks, RtpProfileSource.LEGACY);
        }
    }
}
