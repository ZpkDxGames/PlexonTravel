package com.plexon.travel;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class DestinationRegistry {
    private static final String WORLD_SPAWN_PREFIX = "spawn:";
    private static final String WORLD_HUB_PREFIX = "hub:";
    private static final int MAX_WARP_DISPLAY = 80;

    private final PlexonTravel plugin;
    private final TravelStorage storage;
    private final AtomicReference<Destination> legacySpawn = new AtomicReference<>();
    private final AtomicReference<Destination> legacyHub = new AtomicReference<>();
    private final Map<UUID, Destination> worldSpawns = new ConcurrentHashMap<>();
    private final Map<UUID, Destination> worldHubs = new ConcurrentHashMap<>();
    private final Map<String, Warp> warps = new ConcurrentHashMap<>();
    private final Map<UUID, BackEntry> back = new ConcurrentHashMap<>();

    DestinationRegistry(PlexonTravel plugin, TravelStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    void load() throws Exception {
        for (Map.Entry<String, Destination> entry : storage.loadDestinations().entrySet()) {
            String id = entry.getKey();
            if (id.equals("spawn")) legacySpawn.set(entry.getValue());
            else if (id.equals("hub")) legacyHub.set(entry.getValue());
            else if (id.startsWith(WORLD_SPAWN_PREFIX)) parseWorldDestination(id, WORLD_SPAWN_PREFIX, entry.getValue(), worldSpawns);
            else if (id.startsWith(WORLD_HUB_PREFIX)) parseWorldDestination(id, WORLD_HUB_PREFIX, entry.getValue(), worldHubs);
            else plugin.getLogger().warning("Ignoring unknown destination key: " + id);
        }
        warps.putAll(storage.loadWarps());
        back.putAll(storage.loadBack());
    }

    private void parseWorldDestination(String id, String prefix, Destination destination, Map<UUID, Destination> target) {
        try {
            target.put(UUID.fromString(id.substring(prefix.length())), destination);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Ignoring malformed per-world destination key: " + id);
        }
    }

    Destination spawnFor(World world) {
        if (world == null) return legacySpawn.get();
        Destination configured = worldSpawns.get(world.getUID());
        if (configured != null) return configured;
        FileConfiguration config = plugin.getConfig();
        if (config.getBoolean("destinations.spawn.legacy-global-fallback", true) && legacySpawn.get() != null) return legacySpawn.get();
        String fallback = config.getString("destinations.spawn.fallback", "VANILLA").toUpperCase(Locale.ROOT);
        return fallback.equals("VANILLA") ? Destination.from(world.getSpawnLocation()) : null;
    }

    Destination hubFor(World world) {
        String mode = plugin.getConfig().getString("hub.mode", "PER_WORLD").toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "DISABLED" -> null;
            case "SPAWN" -> spawnFor(world);
            case "GLOBAL" -> legacyHub.get();
            case "PER_WORLD", "SEPARATE" -> perWorldHubWithFallback(world);
            default -> perWorldHubWithFallback(world);
        };
    }

    private Destination perWorldHubWithFallback(World world) {
        Destination configured = world == null ? null : worldHubs.get(world.getUID());
        if (configured != null) return configured;
        String fallback = plugin.getConfig().getString("destinations.hub.fallback", "GLOBAL").toUpperCase(Locale.ROOT);
        if (fallback.equals("SPAWN")) return spawnFor(world);
        if (fallback.equals("NONE")) return null;
        Destination global = legacyHub.get();
        return global != null ? global : spawnFor(world);
    }

    Destination legacySpawn() { return legacySpawn.get(); }
    Destination legacyHub() { return legacyHub.get(); }
    Destination worldSpawn(UUID worldId) { return worldSpawns.get(worldId); }
    Destination worldHub(UUID worldId) { return worldHubs.get(worldId); }

    void setWorldSpawn(World world, Location location) {
        Destination destination = Destination.from(location);
        if (world == null || destination == null) return;
        worldSpawns.put(world.getUID(), destination);
        storage.saveDestinationAsync(WORLD_SPAWN_PREFIX + world.getUID(), destination);
    }

    void setWorldHub(World world, Location location) {
        Destination destination = Destination.from(location);
        if (world == null || destination == null) return;
        worldHubs.put(world.getUID(), destination);
        storage.saveDestinationAsync(WORLD_HUB_PREFIX + world.getUID(), destination);
    }

    void setGlobalSpawn(Location location) {
        Destination destination = Destination.from(location);
        if (destination == null) return;
        legacySpawn.set(destination);
        storage.saveDestinationAsync("spawn", destination);
    }

    void setGlobalHub(Location location) {
        Destination destination = Destination.from(location);
        if (destination == null) return;
        legacyHub.set(destination);
        storage.saveDestinationAsync("hub", destination);
    }

    BackEntry back(UUID playerId) { return back.get(playerId); }

    void setBack(UUID playerId, Location location, String source) {
        if (location == null || location.getWorld() == null || !Destination.finite(location)) return;
        BackEntry entry = new BackEntry(Destination.from(location), source == null ? "unknown" : source, System.currentTimeMillis());
        back.put(playerId, entry);
        storage.saveBackAsync(playerId, entry);
    }

    Warp warp(String id) { return warps.get(normalizeId(id)); }

    List<Warp> warps() {
        return warps.values().stream().sorted(Comparator.comparingInt(Warp::sortOrder).thenComparing(Warp::id)).toList();
    }

    List<Warp> availableWarps() {
        return warps.values().stream().filter(Warp::enabled)
            .sorted(Comparator.comparingInt(Warp::sortOrder).thenComparing(Warp::id)).toList();
    }

    Warp saveWarp(String requestedName, Location location) {
        String id = normalizeId(requestedName);
        Destination destination = Destination.from(location);
        String displayName = boundedDisplay(requestedName);
        if (id.isBlank() || destination == null || displayName == null) return null;
        Warp previous = warps.get(id);
        long revision = previous == null ? 1L : previous.revision() + 1L;
        Warp current = new Warp(id, displayName, destination, true,
            "plexontravel.warp." + id, false, previous == null ? warps.size() : previous.sortOrder(),
            previous == null ? "ENDER_PEARL" : previous.icon(), previous == null ? "Server" : previous.category(), revision);
        warps.put(id, current);
        storage.saveWarpAsync(current);
        return current;
    }

    Warp importWarp(String requestedName, Destination destination, String category) {
        String id = normalizeId(requestedName);
        String displayName = boundedDisplay(requestedName);
        if (id.isBlank() || displayName == null || destination == null || !destination.finite() || warps.containsKey(id)) return null;
        String resolvedCategory = category == null || category.isBlank() ? "Imported" : category.trim();
        if (resolvedCategory.length() > MAX_WARP_DISPLAY) resolvedCategory = resolvedCategory.substring(0, MAX_WARP_DISPLAY);
        Warp warp = new Warp(id, displayName, destination, true, "plexontravel.warp." + id, false,
            warps.size(), "ENDER_PEARL", resolvedCategory, 1L);
        warps.put(id, warp);
        storage.saveWarpAsync(warp);
        return warp;
    }

    Warp renameWarp(String id, String displayName) {
        String normalized = normalizeId(id);
        Warp old = warps.get(normalized);
        String bounded = boundedDisplay(displayName);
        if (old == null || bounded == null) return null;
        Warp renamed = new Warp(old.id(), bounded, old.destination(), old.enabled(), old.permission(),
            old.permissionRequired(), old.sortOrder(), old.icon(), old.category(), old.revision() + 1L);
        warps.put(normalized, renamed);
        storage.saveWarpAsync(renamed);
        return renamed;
    }

    private String boundedDisplay(String displayName) {
        if (displayName == null) return null;
        String trimmed = displayName.trim();
        if (trimmed.isBlank() || trimmed.length() > MAX_WARP_DISPLAY) return null;
        return trimmed;
    }

    boolean removeWarp(Warp expected) {
        if (expected == null || !warps.remove(expected.id(), expected)) return false;
        storage.deleteWarpAsync(expected.id());
        return true;
    }

    int worldSpawnCount() { return worldSpawns.size(); }
    int worldHubCount() { return worldHubs.size(); }
    int warpCount() { return warps.size(); }
    int backCount() { return back.size(); }

    static String normalizeId(String input) {
        if (input == null) return "";
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    List<String> warpIds() { return new ArrayList<>(warps.keySet()); }
}
