package com.plexon.travel;

import com.plexon.travel.event.PlexonVoidRescueEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

final class VoidRescueService implements Listener {
    static final String BYPASS_PERMISSION = "plexontravel.voidrescue.bypass";
    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final PlexonTravel plugin;
    private final DestinationRegistry destinations;
    private final TravelEngine engine;
    private final TravelMessages messages;
    private final WorldSettingsManager worldSettings;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> internalTeleports = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> failureBackoff = new ConcurrentHashMap<>();
    private final LongAdder attempted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failures = new LongAdder();

    VoidRescueService(PlexonTravel plugin, DestinationRegistry destinations, TravelEngine engine,
                      TravelMessages messages, WorldSettingsManager worldSettings) {
        this.plugin = plugin;
        this.destinations = destinations;
        this.engine = engine;
        this.messages = messages;
        this.worldSettings = worldSettings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        Location from = event.getFrom();
        if (from.getWorld() == to.getWorld() && Double.compare(from.getY(), to.getY()) == 0) return;
        World world = to.getWorld();
        VoidRescueRule rule = worldSettings.voidRule(world);
        if (!rule.enabled() || to.getY() > rule.triggerY()) return;
        Player player = event.getPlayer();
        if (!eligible(player, rule)) return;
        UUID playerId = player.getUniqueId();
        Long blockedUntil = failureBackoff.get(playerId);
        if (blockedUntil != null) {
            if (blockedUntil > System.nanoTime()) return;
            failureBackoff.remove(playerId, blockedUntil);
        }
        if (!inFlight.add(playerId)) return;
        rescue(player, world, rule, to.clone());
    }

    private boolean eligible(Player player, VoidRescueRule rule) {
        if (player.hasPermission(BYPASS_PERMISSION)) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR) return false;
        if (mode == GameMode.CREATIVE && !rule.rescueCreative()) return false;
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE || mode == GameMode.CREATIVE;
    }

    private void rescue(Player player, World sourceWorld, VoidRescueRule rule, Location source) {
        attempted.increment();
        Destination destination = resolveDestination(sourceWorld, rule.destination());
        if (destination == null) {
            fail(player, "Void rescue destination could not be resolved.");
            return;
        }
        Location target = destination.toLocation();
        if (target == null) {
            fail(player, "Void rescue target world is unavailable.");
            return;
        }
        if (target.getWorld() != null && target.getWorld().getUID().equals(sourceWorld.getUID()) && target.getY() <= rule.triggerY()) {
            fail(player, "Void rescue destination is at or below its trigger threshold.");
            return;
        }

        PlexonVoidRescueEvent event = new PlexonVoidRescueEvent(player, source, rule.triggerY(), rule.destination().mode().name(), target);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            inFlight.remove(player.getUniqueId());
            return;
        }

        immediateSystemTeleport(player, destination).whenComplete((success, error) -> runSync(() -> {
            UUID playerId = player.getUniqueId();
            inFlight.remove(playerId);
            if (error != null || !Boolean.TRUE.equals(success)) {
                failures.increment();
                failureBackoff.put(playerId, System.nanoTime() + FAILURE_BACKOFF_NANOS);
                messages.send(player, "void-rescue.failed", "<red>Void rescue failed. An administrator should verify this world's rescue destination.</red>");
                if (error != null) plugin.getLogger().warning("Void rescue failed for " + player.getName() + ": " + error.getMessage());
                return;
            }
            completed.increment();
            failureBackoff.remove(playerId);
            player.setFallDistance(0F);
            messages.send(player, "void-rescue.success", "<aqua>You were rescued from the void.</aqua>");
        }));
    }

    private Destination resolveDestination(World sourceWorld, VoidDestination configured) {
        if (configured == null || configured.mode() == null) return null;
        World targetWorld = resolveWorld(configured.targetWorld(), sourceWorld);
        if (targetWorld == null) return null;
        return switch (configured.mode()) {
            case PLEXON_SPAWN -> destinations.spawnFor(targetWorld);
            case PLEXON_HUB -> destinations.hubFor(targetWorld);
            case WORLD_SPAWN -> Destination.from(targetWorld.getSpawnLocation());
            case FIXED -> new Destination(targetWorld.getUID(), targetWorld.getName(), configured.x(), configured.y(), configured.z(),
                configured.yaw(), configured.pitch());
        };
    }

    Destination resolvedDestination(World sourceWorld) {
        return resolveDestination(sourceWorld, worldSettings.voidRule(sourceWorld).destination());
    }

    private World resolveWorld(String configured, World fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        try {
            World byId = Bukkit.getWorld(UUID.fromString(configured));
            if (byId != null) return byId;
        } catch (IllegalArgumentException ignored) { }
        return Bukkit.getWorld(configured);
    }

    private CompletableFuture<Boolean> immediateSystemTeleport(Player player, Destination destination) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Location target = destination.toLocation();
        if (target == null || target.getWorld() == null || !Destination.finite(target)) {
            result.complete(false);
            return result;
        }
        World world = target.getWorld();
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        boolean generate = plugin.getConfig().getBoolean("safe-teleport.generate-chunks", false);
        if (!world.isChunkLoaded(chunkX, chunkZ) && !generate && !world.isChunkGenerated(chunkX, chunkZ)) {
            result.complete(false);
            return result;
        }

        CompletableFuture<?> chunkReady = world.isChunkLoaded(chunkX, chunkZ)
            ? CompletableFuture.completedFuture(Boolean.TRUE)
            : world.getChunkAtAsync(chunkX, chunkZ, generate);
        chunkReady.whenComplete((ignored, chunkFailure) -> runSync(() -> {
            if (chunkFailure != null || !player.isOnline() || player.isDead() || !engine.isSafe(target)) {
                result.complete(false);
                return;
            }
            UUID playerId = player.getUniqueId();
            engine.cancel(playerId, com.plexon.travel.api.PlexonTravelAPI.CancelReason.REPLACED);
            internalTeleports.add(playerId);
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, teleportFailure) -> runSync(() -> {
                internalTeleports.remove(playerId);
                result.complete(teleportFailure == null && Boolean.TRUE.equals(success) && player.isOnline() && !player.isDead());
            }));
        }));
        return result;
    }

    private void fail(Player player, String logMessage) {
        UUID playerId = player.getUniqueId();
        inFlight.remove(playerId);
        failures.increment();
        failureBackoff.put(playerId, System.nanoTime() + FAILURE_BACKOFF_NANOS);
        plugin.getLogger().warning(logMessage + " player=" + player.getName() + " world=" + player.getWorld().getName());
        messages.send(player, "void-rescue.failed", "<red>Void rescue failed. An administrator should verify this world's rescue destination.</red>");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        inFlight.remove(playerId);
        internalTeleports.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        inFlight.remove(playerId);
        internalTeleports.remove(playerId);
        failureBackoff.remove(playerId);
    }

    boolean isInFlight(UUID playerId) { return inFlight.contains(playerId); }
    boolean isInternal(UUID playerId) { return internalTeleports.contains(playerId); }
    long attemptedCount() { return attempted.sum(); }
    long completedCount() { return completed.sum(); }
    long failureCount() { return failures.sum(); }
    int activeCount() { return inFlight.size(); }

    void shutdown() {
        inFlight.clear();
        internalTeleports.clear();
        failureBackoff.clear();
    }

    private void runSync(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
