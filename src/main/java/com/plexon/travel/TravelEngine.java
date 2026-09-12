package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.CancelReason;
import com.plexon.travel.api.PlexonTravelAPI.TravelStatusView;
import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import com.plexon.travel.event.PlexonTravelCancelledEvent;
import com.plexon.travel.event.PlexonTravelCompleteEvent;
import com.plexon.travel.event.PlexonTravelStartEvent;
import com.plexon.travel.internal.AttemptLedger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

final class TravelEngine {
    private final PlexonTravel plugin;
    private final DestinationRegistry destinations;
    private final TravelMessages messages;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AttemptLedger attempts = new AttemptLedger();
    private final Map<UUID, EnumMap<TravelType, Long>> cooldowns = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final SafeResolver safeResolver = new SafeResolver();
    private final EconomyBridge economy = new EconomyBridge();
    private final LongAdder started = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder movementCancelled = new LongAdder();
    private final LongAdder damageCancelled = new LongAdder();
    private final LongAdder unsafeRejected = new LongAdder();
    private BukkitTask ticker;
    private long runtimeEpoch = 1L;

    TravelEngine(PlexonTravel plugin, DestinationRegistry destinations, TravelMessages messages) {
        this.plugin = plugin;
        this.destinations = destinations;
        this.messages = messages;
    }

    void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 5L);
    }

    Policy policy(TravelType type, double warpFeeOverride) {
        String key = type.name().toLowerCase(Locale.ROOT);
        String base = "teleport." + key;
        int defaultWarmup = type == TravelType.ADMIN ? 0 : 5;
        int defaultCooldown = switch (type) {
            case BACK -> 15;
            case RTP -> 120;
            case ADMIN -> 0;
            default -> 10;
        };
        int warmup = Math.max(0, plugin.getConfig().getInt(base + ".warmup-seconds", defaultWarmup));
        int cooldown = Math.max(0, plugin.getConfig().getInt(base + ".cooldown-seconds", defaultCooldown));
        double fee;
        if (type == TravelType.WARP) {
            fee = warpFeeOverride >= 0 ? warpFeeOverride : Math.max(0D, plugin.getConfig().getDouble(base + ".default-fee", 0D));
        } else {
            fee = Math.max(0D, plugin.getConfig().getDouble(base + ".fee", 0D));
        }
        return new Policy(warmup, cooldown, fee);
    }

    CompletableFuture<Boolean> request(Player player, TravelType type, Destination destination, String sourceId, double feeOverride) {
        return request(player, type, destination, sourceId, policy(type, feeOverride));
    }

    CompletableFuture<Boolean> request(Player player, TravelType type, Destination destination, String sourceId, Policy policy) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (destination == null) {
            messages.send(player, "destination.missing", "<red>That destination is not configured.</red>");
            result.complete(false);
            return result;
        }
        if (!player.isOnline()) {
            result.complete(false);
            return result;
        }
        if (!cooldownReady(player, type, policy.cooldownSeconds())) {
            result.complete(false);
            return result;
        }

        UUID playerId = player.getUniqueId();
        long attemptId = attempts.acquire(playerId);
        if (attemptId == 0L) {
            messages.send(player, "teleport.already-pending", "<red>A teleport is already pending or in progress.</red>");
            result.complete(false);
            return result;
        }

        long requestEpoch = runtimeEpoch;
        safeResolver.resolve(destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(plugin,
            () -> beginResolved(player, type, destination, sourceId, policy, safe, error, attemptId, requestEpoch, result)));
        return result;
    }

    private void beginResolved(Player player, TravelType type, Destination destination, String sourceId, Policy policy,
                               Location safe, Throwable error, long attemptId, long requestEpoch, CompletableFuture<Boolean> result) {
        UUID playerId = player.getUniqueId();
        if (!attempts.owns(playerId, attemptId) || requestEpoch != runtimeEpoch) {
            attempts.release(playerId, attemptId);
            result.complete(false);
            return;
        }
        if (!player.isOnline()) {
            attempts.release(playerId, attemptId);
            result.complete(false);
            return;
        }
        if (error != null || safe == null) {
            unsafeRejected.increment();
            attempts.release(playerId, attemptId);
            messages.send(player, "destination.unsafe", "<red>No safe destination is available.</red>");
            result.complete(false);
            return;
        }

        PlexonTravelStartEvent start = new PlexonTravelStartEvent(player, type, destination.view(), sourceId);
        Bukkit.getPluginManager().callEvent(start);
        if (start.isCancelled()) {
            attempts.release(playerId, attemptId);
            result.complete(false);
            return;
        }

        long now = System.nanoTime();
        long duration = TimeUnit.SECONDS.toNanos(policy.warmupSeconds());
        BossBar bar = null;
        if (duration > 0) {
            bar = Bukkit.createBossBar(messages.legacy(messages.render("bossbar.teleport", "<aqua>Teleporting</aqua> <gray>in {seconds}s</gray>", Map.of("seconds", policy.warmupSeconds()))),
                BarColor.BLUE, BarStyle.SOLID);
            bar.setProgress(1D);
            bar.addPlayer(player);
        }

        Location origin = player.getLocation().clone();
        Pending created = new Pending(player, type, destination, sourceId, policy, origin, now, now + duration,
            requestEpoch, attemptId, result, bar);
        pending.put(playerId, created);
        started.increment();
        warmupParticle(created);

        if (duration == 0) {
            execute(created);
        } else {
            String fee = policy.fee() > 0D && !player.hasPermission("plexontravel.fee.bypass")
                ? String.format(Locale.ROOT, "%.2f", policy.fee()) : "0";
            messages.send(player, "teleport.warmup",
                "<gray>Teleporting in <aqua>{seconds}s</aqua>. Move or take damage to cancel.</gray>",
                Map.of("seconds", policy.warmupSeconds(), "fee", fee, "type", type.name().toLowerCase(Locale.ROOT)));
        }
    }

    private boolean cooldownReady(Player player, TravelType type, int seconds) {
        if (seconds <= 0 || player.hasPermission("plexontravel.cooldown.bypass")) return true;
        EnumMap<TravelType, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) return true;
        long until = plugin.getConfig().getString("cooldowns.mode", "PER_TYPE").equalsIgnoreCase("GLOBAL")
            ? map.values().stream().mapToLong(Long::longValue).max().orElse(0L)
            : map.getOrDefault(type, 0L);
        long remaining = until - System.nanoTime();
        if (remaining <= 0) return true;
        long secondsLeft = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining) + 1);
        messages.send(player, "teleport.cooldown", "<yellow>Wait <white>{seconds}s</white> before travelling again.</yellow>",
            Map.of("seconds", secondsLeft));
        return false;
    }

    long cooldownRemainingMillis(Player player, TravelType type) {
        EnumMap<TravelType, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) return 0L;
        long until = plugin.getConfig().getString("cooldowns.mode", "PER_TYPE").equalsIgnoreCase("GLOBAL")
            ? map.values().stream().mapToLong(Long::longValue).max().orElse(0L)
            : map.getOrDefault(type, 0L);
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()));
    }

    private void startCooldown(Player player, TravelType type, int seconds) {
        if (seconds <= 0 || player.hasPermission("plexontravel.cooldown.bypass")) return;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        EnumMap<TravelType, Long> map = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(TravelType.class));
        if (plugin.getConfig().getString("cooldowns.mode", "PER_TYPE").equalsIgnoreCase("GLOBAL")) {
            for (TravelType value : TravelType.values()) map.put(value, until);
        } else {
            map.put(type, until);
        }
    }

    private void tick() {
        long now = System.nanoTime();
        for (Pending value : pending.values()) {
            if (value.executing) continue;
            if (value.epoch != runtimeEpoch) {
                cancel(value.player.getUniqueId(), CancelReason.REPLACED);
                continue;
            }
            if (now >= value.deadlineNanos) {
                execute(value);
                continue;
            }
            if (value.bar != null) {
                double total = Math.max(1D, value.deadlineNanos - value.startedNanos);
                double remaining = Math.max(0D, value.deadlineNanos - now);
                value.bar.setProgress(Math.max(0D, Math.min(1D, remaining / total)));
                long seconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds((long) remaining) + 1L);
                value.bar.setTitle(messages.legacy(messages.render("bossbar.teleport", "<aqua>Teleporting</aqua> <gray>in {seconds}s</gray>", Map.of("seconds", seconds))));
            }
            warmupParticle(value);
        }
    }

    private void warmupParticle(Pending value) {
        if (!plugin.getConfig().getBoolean("effects.warmup.particles", true) || !value.player.isOnline()) return;
        Location at = value.player.getLocation().add(0D, 0.8D, 0D);
        int count = Math.max(1, Math.min(40, plugin.getConfig().getInt("effects.warmup.particle-count", 8)));
        value.player.spawnParticle(Particle.PORTAL, at, count, 0.35D, 0.55D, 0.35D, 0.02D);
    }

    private void completionEffect(Player player) {
        if (plugin.getConfig().getBoolean("effects.complete.particles", true)) {
            Location at = player.getLocation().add(0D, 0.8D, 0D);
            int count = Math.max(1, Math.min(80, plugin.getConfig().getInt("effects.complete.particle-count", 28)));
            player.spawnParticle(Particle.PORTAL, at, count, 0.45D, 0.7D, 0.45D, 0.08D);
            player.spawnParticle(Particle.END_ROD, at, Math.max(2, count / 4), 0.35D, 0.55D, 0.35D, 0.02D);
        }
        if (plugin.getConfig().getBoolean("effects.complete.sound", true)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.1F);
        }
    }

    void onMove(Player player, Location to) {
        Pending value = pending.get(player.getUniqueId());
        if (value == null || value.executing || to == null) return;
        double threshold = Math.max(0D, plugin.getConfig().getDouble("teleport.movement-threshold", 0.01D));
        double dx = to.getX() - value.origin.getX();
        double dy = to.getY() - value.origin.getY();
        double dz = to.getZ() - value.origin.getZ();
        if (dx * dx + dy * dy + dz * dz > threshold * threshold) {
            movementCancelled.increment();
            cancel(player.getUniqueId(), CancelReason.MOVED);
        }
    }

    boolean cancel(UUID playerId, CancelReason reason) {
        Pending value = pending.get(playerId);
        if (value == null) return attempts.releaseCurrent(playerId);
        if (value.executing) return false;
        if (!pending.remove(playerId, value)) return false;
        attempts.release(playerId, value.attemptId);
        if (reason == CancelReason.DAMAGED) damageCancelled.increment();
        finishCancelled(value, reason);
        return true;
    }

    private void finishCancelled(Pending value, CancelReason reason) {
        if (value.bar != null) value.bar.removeAll();
        if (!value.result.isDone()) value.result.complete(false);
        Bukkit.getPluginManager().callEvent(new PlexonTravelCancelledEvent(value.player, value.type, reason, value.sourceId));
        switch (reason) {
            case MOVED -> messages.send(value.player, "teleport.cancelled-moved", "<red>Teleport cancelled because you moved.</red>");
            case DAMAGED -> messages.send(value.player, "teleport.cancelled-damage", "<red>Teleport cancelled because you took damage.</red>");
            case DESTINATION_UNSAFE -> messages.send(value.player, "destination.unsafe", "<red>The destination is no longer safe.</red>");
            default -> { }
        }
    }

    private void execute(Pending value) {
        UUID playerId = value.player.getUniqueId();
        if (pending.get(playerId) != value || value.executing) return;
        value.executing = true;
        safeResolver.resolve(value.destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (pending.get(playerId) != value) return;
            if (error != null || safe == null) {
                unsafeRejected.increment();
                pending.remove(playerId, value);
                attempts.release(playerId, value.attemptId);
                finishCancelled(value, CancelReason.DESTINATION_UNSAFE);
                return;
            }

            double fee = value.player.hasPermission("plexontravel.fee.bypass") ? 0D : value.policy.fee();
            if (fee > 0D && !economy.withdraw(value.player, fee)) {
                pending.remove(playerId, value);
                attempts.release(playerId, value.attemptId);
                finishCancelled(value, CancelReason.ECONOMY);
                messages.send(value.player, "teleport.fee-failed", "<red>Unable to charge the teleport fee.</red>");
                return;
            }
            value.chargedFee = fee;
            internalTeleports.add(playerId);
            value.player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, teleportError) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishTeleport(value, playerId, success, teleportError)));
        }));
    }

    private void finishTeleport(Pending value, UUID playerId, Boolean success, Throwable teleportError) {
        internalTeleports.remove(playerId);
        if (pending.get(playerId) != value || !attempts.owns(playerId, value.attemptId)) return;
        pending.remove(playerId, value);
        attempts.release(playerId, value.attemptId);
        if (value.bar != null) value.bar.removeAll();

        if (Boolean.TRUE.equals(success) && teleportError == null) {
            destinations.setBack(playerId, value.origin, "travel:" + value.type.name().toLowerCase(Locale.ROOT));
            startCooldown(value.player, value.type, value.policy.cooldownSeconds());
            completed.increment();
            Bukkit.getPluginManager().callEvent(new PlexonTravelCompleteEvent(value.player, value.type, value.destination.view(), value.sourceId));
            completionEffect(value.player);
            messages.send(value.player, "teleport.complete", "<green>Teleport complete.</green>");
            value.result.complete(true);
        } else {
            refundOnce(value);
            Bukkit.getPluginManager().callEvent(new PlexonTravelCancelledEvent(value.player, value.type, CancelReason.OTHER, value.sourceId));
            messages.send(value.player, "teleport.failed", "<red>Teleport failed; any fee was refunded.</red>");
            value.result.complete(false);
        }
    }

    void directJoinTeleport(Player player, Destination destination) {
        safeResolver.resolve(destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || error != null || safe == null) return;
            UUID playerId = player.getUniqueId();
            internalTeleports.add(playerId);
            player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> internalTeleports.remove(playerId)));
        }));
    }

    private void refundOnce(Pending value) {
        if (value.refunded || value.chargedFee <= 0D) return;
        value.refunded = true;
        economy.deposit(value.player, value.chargedFee);
    }

    boolean isInternal(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    boolean isPending(UUID playerId) {
        return attempts.isActive(playerId);
    }

    TravelStatusView status(UUID playerId) {
        Pending value = pending.get(playerId);
        if (value == null) return TravelStatusView.idle();
        return new TravelStatusView(true, value.type, value.sourceId,
            Math.max(0L, TimeUnit.NANOSECONDS.toMillis(value.deadlineNanos - System.nanoTime())));
    }

    void invalidateWarmupsForReload() {
        runtimeEpoch++;
        for (Pending value : List.copyOf(pending.values())) {
            if (!value.executing) cancel(value.player.getUniqueId(), CancelReason.REPLACED);
        }
    }

    long runtimeEpoch() {
        return runtimeEpoch;
    }

    long startedCount() { return started.sum(); }
    long completedCount() { return completed.sum(); }
    long movedCancelledCount() { return movementCancelled.sum(); }
    long damageCancelledCount() { return damageCancelled.sum(); }
    long unsafeRejectedCount() { return unsafeRejected.sum(); }
    int pendingCount() { return attempts.size(); }

    boolean isSafe(Location location) {
        return safeResolver.safe(location.getWorld(), location);
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        for (Pending value : List.copyOf(pending.values())) {
            if (value.executing) {
                refundOnce(value);
                if (value.bar != null) value.bar.removeAll();
                if (!value.result.isDone()) value.result.complete(false);
            } else {
                cancel(value.player.getUniqueId(), CancelReason.PLUGIN_DISABLED);
            }
        }
        pending.clear();
        attempts.clear();
        internalTeleports.clear();
    }

    private final class SafeResolver {
        private final Set<Material> danger = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH);

        CompletableFuture<Location> resolve(Destination destination) {
            CompletableFuture<Location> future = new CompletableFuture<>();
            if (destination == null || !destination.finite()) {
                future.complete(null);
                return future;
            }
            World world = Bukkit.getWorld(destination.worldId());
            if (world == null) world = Bukkit.getWorld(destination.worldName());
            if (world == null) {
                future.complete(null);
                return future;
            }
            World targetWorld = world;
            Location target = new Location(world, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                future.complete(resolveNow(targetWorld, target));
            } else {
                world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) future.completeExceptionally(error);
                    else future.complete(resolveNow(targetWorld, target));
                }));
            }
            return future;
        }

        private Location resolveNow(World world, Location requested) {
            if (!plugin.getConfig().getBoolean("safe-teleport.enabled", true)) return requested;
            if (safe(world, requested)) return requested;
            if (!plugin.getConfig().getBoolean("safe-teleport.search-nearby", true)) return null;
            int horizontal = Math.max(0, Math.min(8, plugin.getConfig().getInt("safe-teleport.horizontal-radius", 3)));
            int vertical = Math.max(0, Math.min(12, plugin.getConfig().getInt("safe-teleport.vertical-radius", 4)));
            int baseX = requested.getBlockX();
            int baseY = requested.getBlockY();
            int baseZ = requested.getBlockZ();
            for (int dy = 0; dy <= vertical; dy++) {
                for (int sign : dy == 0 ? new int[]{1} : new int[]{1, -1}) {
                    int y = baseY + dy * sign;
                    for (int radius = 0; radius <= horizontal; radius++) {
                        for (int x = baseX - radius; x <= baseX + radius; x++) {
                            for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                                if (radius > 0 && x != baseX - radius && x != baseX + radius && z != baseZ - radius && z != baseZ + radius) continue;
                                Location candidate = new Location(world, x + 0.5D, y, z + 0.5D, requested.getYaw(), requested.getPitch());
                                if (safe(world, candidate)) return candidate;
                            }
                        }
                    }
                }
            }
            return null;
        }

        private boolean safe(World world, Location location) {
            if (world == null || location == null) return false;
            int y = location.getBlockY();
            if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
            if (!world.getWorldBorder().isInside(location)) return false;
            Block feet = world.getBlockAt(location.getBlockX(), y, location.getBlockZ());
            Block head = world.getBlockAt(location.getBlockX(), y + 1, location.getBlockZ());
            Block support = world.getBlockAt(location.getBlockX(), y - 1, location.getBlockZ());
            return feet.isPassable() && head.isPassable() && support.getType().isSolid()
                && !danger.contains(feet.getType()) && !danger.contains(head.getType()) && !danger.contains(support.getType());
        }
    }

    private final class EconomyBridge {
        private Object provider() {
            try {
                Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration((Class) type);
                return registration == null ? null : registration.getProvider();
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean withdraw(Player player, double amount) {
            if (amount <= 0D) return true;
            try {
                Object provider = provider();
                if (provider == null) return false;
                Method has = provider.getClass().getMethod("has", OfflinePlayer.class, double.class);
                if (!Boolean.TRUE.equals(has.invoke(provider, player, amount))) return false;
                Object response = provider.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(provider, player, amount);
                return Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.WARNING, "Vault withdrawal failed", failure);
                return false;
            }
        }

        void deposit(Player player, double amount) {
            if (amount <= 0D) return;
            try {
                Object provider = provider();
                if (provider != null) {
                    provider.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(provider, player, amount);
                }
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.WARNING, "Vault refund failed", failure);
            }
        }
    }

    private static final class Pending {
        final Player player;
        final TravelType type;
        final Destination destination;
        final String sourceId;
        final Policy policy;
        final Location origin;
        final long startedNanos;
        final long deadlineNanos;
        final long epoch;
        final long attemptId;
        final CompletableFuture<Boolean> result;
        final BossBar bar;
        boolean executing;
        double chargedFee;
        boolean refunded;

        Pending(Player player, TravelType type, Destination destination, String sourceId, Policy policy, Location origin,
                long startedNanos, long deadlineNanos, long epoch, long attemptId, CompletableFuture<Boolean> result, BossBar bar) {
            this.player = player;
            this.type = type;
            this.destination = destination;
            this.sourceId = sourceId;
            this.policy = policy;
            this.origin = origin;
            this.startedNanos = startedNanos;
            this.deadlineNanos = deadlineNanos;
            this.epoch = epoch;
            this.attemptId = attemptId;
            this.result = result;
            this.bar = bar;
        }
    }
}
