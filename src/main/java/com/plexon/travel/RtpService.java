package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

final class RtpService {
    private final PlexonTravel plugin;
    private final TravelEngine engine;
    private final TravelMessages messages;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private final LongAdder searches = new LongAdder();
    private final LongAdder found = new LongAdder();
    private final LongAdder exhausted = new LongAdder();

    RtpService(PlexonTravel plugin, TravelEngine engine, TravelMessages messages) {
        this.plugin = plugin;
        this.engine = engine;
        this.messages = messages;
    }

    boolean begin(Player player) {
        if (!plugin.getConfig().getBoolean("rtp.enabled", true)) {
            messages.send(player, "rtp.disabled", "<red>Random teleport is disabled.</red>");
            return false;
        }
        if (!isAllowed(player.getWorld())) {
            messages.send(player, "rtp.world-not-allowed", "<red>RTP is not available in this world.</red>");
            return false;
        }
        long remaining = engine.cooldownRemainingMillis(player, TravelType.RTP);
        if (remaining > 0L && !player.hasPermission("plexontravel.cooldown.bypass")) {
            messages.send(player, "teleport.cooldown", "<yellow>Wait <white>{seconds}s</white> before travelling again.</yellow>",
                Map.of("seconds", Math.max(1L, (remaining + 999L) / 1000L)));
            return false;
        }
        if (!searching.add(player.getUniqueId())) {
            messages.send(player, "rtp.already-searching", "<yellow>A safe RTP destination is already being searched.</yellow>");
            return false;
        }

        searches.increment();
        messages.send(player, "rtp.searching", "<gray>Searching for a safe location...</gray>");
        World world = player.getWorld();
        search(world).whenComplete((destination, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (!searching.remove(playerId)) return;
            if (!player.isOnline()) return;
            if (failure != null || destination == null) {
                exhausted.increment();
                messages.send(player, "rtp.failed", "<red>No safe RTP location was found. Try again.</red>");
                return;
            }
            found.increment();
            engine.request(player, TravelType.RTP, destination, "rtp:" + world.getName(), -1D);
        }));
        return true;
    }

    void cancel(UUID playerId) {
        searching.remove(playerId);
    }

    boolean isSearching(UUID playerId) {
        return searching.contains(playerId);
    }

    boolean isAllowed(World world) {
        List<String> configured = plugin.getConfig().getStringList("rtp.allowed-worlds");
        if (configured.isEmpty()) configured = List.of("Survival_World");
        String name = world.getName().toLowerCase(Locale.ROOT);
        return configured.stream().anyMatch(value -> value.equalsIgnoreCase(name));
    }

    double minRadius() {
        return Math.max(0D, plugin.getConfig().getDouble("rtp.min-radius", 2000D));
    }

    double maxRadius() {
        return Math.max(minRadius() + 1D, plugin.getConfig().getDouble("rtp.max-radius", 10000D));
    }

    Location center(World world) {
        String mode = plugin.getConfig().getString("rtp.center.mode", "WORLD_SPAWN").toUpperCase(Locale.ROOT);
        if (mode.equals("CONFIGURED")) {
            return new Location(world, plugin.getConfig().getDouble("rtp.center.x", 0D), world.getSpawnLocation().getY(),
                plugin.getConfig().getDouble("rtp.center.z", 0D));
        }
        return world.getSpawnLocation();
    }

    private CompletableFuture<Destination> search(World world) {
        CompletableFuture<Destination> future = new CompletableFuture<>();
        Location center = center(world);
        int attempts = Math.max(1, Math.min(64, plugin.getConfig().getInt("rtp.max-attempts", 24)));
        searchAttempt(world, center.getX(), center.getZ(), minRadius(), maxRadius(), 0, attempts, future);
        return future;
    }

    private void searchAttempt(World world, double centerX, double centerZ, double minRadius, double maxRadius,
                               int attempt, int maxAttempts, CompletableFuture<Destination> future) {
        if (future.isDone()) return;
        if (attempt >= maxAttempts) {
            future.complete(null);
            return;
        }

        RtpGeometry.Point point = RtpGeometry.sampleAnnulus(centerX, centerZ, minRadius, maxRadius, ThreadLocalRandom.current());
        int chunkX = point.x() >> 4;
        int chunkZ = point.z() >> 4;
        Location borderProbe = new Location(world, point.x() + 0.5D, world.getSpawnLocation().getY(), point.z() + 0.5D);
        if (!world.getWorldBorder().isInside(borderProbe)) {
            searchAttempt(world, centerX, centerZ, minRadius, maxRadius, attempt + 1, maxAttempts, future);
            return;
        }

        boolean generate = plugin.getConfig().getBoolean("rtp.generate-chunks", false);
        if (!generate && !world.isChunkGenerated(chunkX, chunkZ)) {
            searchAttempt(world, centerX, centerZ, minRadius, maxRadius, attempt + 1, maxAttempts, future);
            return;
        }

        world.getChunkAtAsync(chunkX, chunkZ, generate).whenComplete((chunk, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (future.isDone()) return;
            if (failure != null || chunk == null) {
                searchAttempt(world, centerX, centerZ, minRadius, maxRadius, attempt + 1, maxAttempts, future);
                return;
            }
            int y = world.getHighestBlockYAt(point.x(), point.z(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Location candidate = new Location(world, point.x() + 0.5D, y, point.z() + 0.5D,
                ThreadLocalRandom.current().nextFloat() * 360F - 180F, 0F);
            if (engine.isSafe(candidate)) {
                future.complete(Destination.from(candidate));
            } else {
                searchAttempt(world, centerX, centerZ, minRadius, maxRadius, attempt + 1, maxAttempts, future);
            }
        }));
    }

    long searchCount() { return searches.sum(); }
    long foundCount() { return found.sum(); }
    long exhaustedCount() { return exhausted.sum(); }
    int activeSearches() { return searching.size(); }
}
