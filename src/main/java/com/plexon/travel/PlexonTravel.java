package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI;
import com.plexon.travel.api.PlexonTravelAPI.BackLocationView;
import com.plexon.travel.api.PlexonTravelAPI.CancelReason;
import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import com.plexon.travel.api.PlexonTravelAPI.TravelStatusView;
import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import com.plexon.travel.api.PlexonTravelAPI.WarpView;
import com.plexon.travel.event.PlexonTravelCancelledEvent;
import com.plexon.travel.event.PlexonTravelCompleteEvent;
import com.plexon.travel.event.PlexonTravelStartEvent;
import com.plexon.travel.event.PlexonWarpCreatedEvent;
import com.plexon.travel.event.PlexonWarpDeletedEvent;
import com.plexon.travel.event.PlexonWarpUpdatedEvent;
import com.plexon.travel.internal.AttemptLedger;
import com.plexon.travel.internal.DestructiveConfirmationGate;
import com.plexon.travel.internal.TravelConfigValidator;
import com.plexon.travel.papi.PlexonTravelExpansion;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

public final class PlexonTravel extends JavaPlugin implements Listener {
    private static final String MODULE_ID = "travel";
    private static final String GUI_TITLE = "Plexon Warps";

    private PlexonCoreAPI core;
    private Storage storage;
    private final AtomicReference<Destination> spawn = new AtomicReference<>();
    private final AtomicReference<Destination> hub = new AtomicReference<>();
    private final Map<String, Warp> warps = new ConcurrentHashMap<>();
    private final Map<UUID, BackEntry> back = new ConcurrentHashMap<>();
    private TravelService travel;
    private WarpGui warpGui;
    private TravelCommands commands;
    private PublicApi publicApi;
    private long runtimeEpoch = 1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfAbsent("messages.yml");
        saveResourceIfAbsent("gui.yml");
        saveResourceIfAbsent("migration.yml");

        List<String> startupConfigErrors = TravelConfigValidator.validate(getConfig());
        if (!startupConfigErrors.isEmpty()) {
            getLogger().severe("Invalid PlexonTravel configuration: " + String.join("; ", startupConfigErrors));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RegisteredServiceProvider<PlexonCoreAPI> coreRegistration = getServer().getServicesManager().getRegistration(PlexonCoreAPI.class);
        if (coreRegistration == null || coreRegistration.getProvider() == null) {
            getLogger().severe("PlexonCore API service is unavailable; PlexonTravel cannot start.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = coreRegistration.getProvider();
        if (!ModuleVersionRange.parse(">=2.0 <3.0").contains(core.version())) {
            getLogger().severe("PlexonCore API " + core.version().apiVersion() + " is outside supported range >=2.0 <3.0");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ModuleRegistry.RegistrationResult registration = core.modules().register(new ModuleDescriptor(
            MODULE_ID,
            "PlexonTravel",
            getName(),
            getPluginMeta().getVersion(),
            this,
            ModuleVersionRange.parse(">=2.0 <3.0"),
            Set.of("spawn", "hub", "back", "warps", "safe-teleport", "teleport-warmup", "travel-api", "travel-events", "sqlite-persistence"),
            ModuleState.STARTING,
            "Initializing travel runtime",
            Instant.now()
        ));
        if (!registration.success()) {
            getLogger().severe("Core module registration failed: " + registration.message());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            storage = new Storage(getDataFolder().toPath().resolve("travel.db"));
            storage.open();
            spawn.set(storage.loadDestination("spawn").orElse(null));
            hub.set(storage.loadDestination("hub").orElse(null));
            warps.putAll(storage.loadWarps());
            back.putAll(storage.loadBack());
        } catch (Exception failure) {
            core.modules().updateState(MODULE_ID, this, ModuleState.FAILED, "Persistence startup failed: " + failure.getMessage());
            getLogger().log(Level.SEVERE, "Failed to initialize travel persistence", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        travel = new TravelService();
        warpGui = new WarpGui();
        commands = new TravelCommands();
        publicApi = new PublicApi();
        getServer().getServicesManager().register(PlexonTravelAPI.class, publicApi, this, ServicePriority.Normal);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new PlexonTravelExpansion(this, publicApi).register();
                getLogger().info("PlaceholderAPI expansion registered.");
            } catch (Throwable failure) {
                getLogger().log(Level.WARNING, "PlaceholderAPI expansion registration failed", failure);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(warpGui, this);
        registerCommands();
        travel.startTicker();

        core.modules().updateState(MODULE_ID, this, ModuleState.READY,
            "Core " + core.version().pluginVersion() + " / API " + core.version().apiVersion() + "; " + warps.size() + " warps cached");
        getLogger().info("PlexonTravel " + getPluginMeta().getVersion() + " enabled against PlexonCore " + core.version().pluginVersion());
    }

    @Override
    public void onDisable() {
        if (travel != null) travel.shutdown();
        if (getServer() != null) getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            try { core.modules().updateState(MODULE_ID, this, ModuleState.DISABLED, "Plugin disabled"); } catch (Throwable ignored) {}
            try { core.modules().unregisterOwnedBy(this); } catch (Throwable ignored) {}
        }
        if (storage != null) storage.close();
    }

    private void registerCommands() {
        for (String name : List.of("spawn", "hub", "back", "warp", "warps", "setspawn", "sethub", "setwarp", "delwarp", "renamewarp", "ptravel", "traveladmin")) {
            PluginCommand command = getCommand(name);
            if (command == null) throw new IllegalStateException("Missing command in plugin.yml: " + name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
    }

    private void saveResourceIfAbsent(String resource) {
        File target = new File(getDataFolder(), resource);
        if (!target.exists()) saveResource(resource, false);
    }

    private boolean standardCommandsEnabled() {
        return getConfig().getBoolean("migration.claim-standard-commands", false);
    }

    private void tell(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[PlexonTravel] " + message));
    }

    private Policy policy(TravelType type, double warpFeeOverride) {
        String key = type.name().toLowerCase(Locale.ROOT);
        String base = "teleport." + key;
        int warmup = Math.max(0, getConfig().getInt(base + ".warmup-seconds", type == TravelType.BACK ? 5 : 3));
        int cooldown = Math.max(0, getConfig().getInt(base + ".cooldown-seconds", type == TravelType.BACK ? 15 : 10));
        double fee;
        if (type == TravelType.WARP) fee = warpFeeOverride >= 0 ? warpFeeOverride : Math.max(0D, getConfig().getDouble(base + ".default-fee", 0D));
        else fee = Math.max(0D, getConfig().getDouble(base + ".fee", 0D));
        return new Policy(warmup, cooldown, fee);
    }

    private Destination resolvedHub() {
        return switch (getConfig().getString("hub.mode", "SEPARATE").toUpperCase(Locale.ROOT)) {
            case "SPAWN" -> spawn.get();
            case "DISABLED" -> null;
            default -> hub.get();
        };
    }

    private boolean request(Player player, TravelType type, Destination destination, String sourceId, double feeOverride) {
        if (destination == null) {
            tell(player, type == TravelType.HUB ? "Hub is not configured." : "Destination is not configured.");
            return false;
        }
        travel.request(player, type, destination, sourceId, policy(type, feeOverride));
        return true;
    }

    private void setBack(UUID playerId, Location location, String source) {
        if (location == null || location.getWorld() == null || !Destination.finite(location)) return;
        BackEntry entry = new BackEntry(Destination.from(location), source, System.currentTimeMillis());
        back.put(playerId, entry);
        storage.saveBackAsync(playerId, entry);
    }

    private void fire(org.bukkit.event.Event event) { getServer().getPluginManager().callEvent(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (travel == null || event.getTo() == null) return;
        travel.onMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (travel != null && event.getEntity() instanceof Player player) travel.cancel(player.getUniqueId(), CancelReason.DAMAGED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (travel != null) travel.cancel(event.getPlayer().getUniqueId(), CancelReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (travel != null) travel.cancel(player.getUniqueId(), CancelReason.DIED);
        if (getConfig().getBoolean("back.capture.deaths", true)) setBack(player.getUniqueId(), player.getLocation(), "death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (travel == null || travel.isInternal(event.getPlayer().getUniqueId())) return;
        travel.cancel(event.getPlayer().getUniqueId(), CancelReason.WORLD_CHANGED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (travel == null) return;
        UUID id = event.getPlayer().getUniqueId();
        if (travel.isInternal(id)) return;
        travel.cancel(id, CancelReason.REPLACED);
        if (getConfig().getBoolean("back.capture.external-teleports", true)) {
            boolean portal = event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL;
            if (!portal || getConfig().getBoolean("back.capture.portals", false)) setBack(id, event.getFrom(), "external:" + event.getCause().name().toLowerCase(Locale.ROOT));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        String mode = getConfig().getString("respawn.mode", "VANILLA").toUpperCase(Locale.ROOT);
        Destination destination = null;
        if (mode.equals("SPAWN")) destination = spawn.get();
        else if (mode.equals("HUB")) destination = resolvedHub();
        else if (mode.equals("LAST_BED_OR_SPAWN") && !event.isBedSpawn() && !event.isAnchorSpawn()) destination = spawn.get();
        if (destination == null) return;
        Location loc = destination.toLocation();
        if (loc != null) event.setRespawnLocation(loc);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("join.teleport-to-spawn", false)) return;
        Destination destination = spawn.get();
        if (destination == null) return;
        Bukkit.getScheduler().runTask(this, () -> travel.directJoinTeleport(event.getPlayer(), destination));
    }

    private final class TravelService {
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

        void startTicker() { ticker = Bukkit.getScheduler().runTaskTimer(PlexonTravel.this, this::tick, 1L, 5L); }

        CompletableFuture<Boolean> request(Player player, TravelType type, Destination destination, String sourceId, Policy policy) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            if (!player.isOnline()) { result.complete(false); return result; }
            if (!cooldownReady(player, type, policy.cooldownSeconds())) { result.complete(false); return result; }
            UUID playerId = player.getUniqueId();
            long attemptId = attempts.acquire(playerId);
            if (attemptId == 0L) {
                tell(player, "A teleport is already pending or in progress.");
                result.complete(false);
                return result;
            }
            long requestEpoch = runtimeEpoch;
            safeResolver.resolve(destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(PlexonTravel.this,
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
            if (!player.isOnline()) { attempts.release(playerId, attemptId); result.complete(false); return; }
            if (error != null || safe == null) {
                unsafeRejected.increment();
                attempts.release(playerId, attemptId);
                tell(player, "No safe destination is available.");
                result.complete(false);
                return;
            }
            PlexonTravelStartEvent start = new PlexonTravelStartEvent(player, type, destination.view(), sourceId);
            fire(start);
            if (start.isCancelled()) { attempts.release(playerId, attemptId); result.complete(false); return; }

            long now = System.nanoTime();
            long duration = TimeUnit.SECONDS.toNanos(policy.warmupSeconds());
            BossBar bar = null;
            if (duration > 0) {
                bar = Bukkit.createBossBar("Teleporting...", BarColor.BLUE, BarStyle.SOLID);
                bar.setProgress(1D);
                bar.addPlayer(player);
            }
            Location origin = player.getLocation();
            Pending created = new Pending(player, type, destination, sourceId, policy, origin, now, now + duration, requestEpoch, attemptId, result, bar);
            pending.put(playerId, created);
            started.increment();
            if (duration == 0) execute(created);
            else {
                String feeText = policy.fee() > 0D && !player.hasPermission("plexontravel.fee.bypass") ? String.format(Locale.ROOT, " Fee: %.2f.", policy.fee()) : "";
                tell(player, "Teleporting in " + policy.warmupSeconds() + "s." + feeText + " Move or take damage to cancel.");
            }
        }

        private boolean cooldownReady(Player player, TravelType type, int seconds) {
            if (seconds <= 0 || player.hasPermission("plexontravel.cooldown.bypass")) return true;
            EnumMap<TravelType, Long> map = cooldowns.get(player.getUniqueId());
            if (map == null) return true;
            long until = getConfig().getString("cooldowns.mode", "PER_TYPE").equalsIgnoreCase("GLOBAL")
                ? map.values().stream().mapToLong(Long::longValue).max().orElse(0L)
                : map.getOrDefault(type, 0L);
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return true;
            tell(player, "Cooldown: " + Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining) + 1) + "s remaining.");
            return false;
        }

        private void startCooldown(Player player, TravelType type, int seconds) {
            if (seconds <= 0 || player.hasPermission("plexontravel.cooldown.bypass")) return;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            EnumMap<TravelType, Long> map = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(TravelType.class));
            if (getConfig().getString("cooldowns.mode", "PER_TYPE").equalsIgnoreCase("GLOBAL")) {
                for (TravelType value : TravelType.values()) map.put(value, until);
            } else map.put(type, until);
        }

        void tick() {
            long now = System.nanoTime();
            for (Pending value : pending.values()) {
                if (value.executing) continue;
                if (value.epoch != runtimeEpoch) { cancel(value.player.getUniqueId(), CancelReason.REPLACED); continue; }
                if (now >= value.deadlineNanos) { execute(value); continue; }
                if (value.bar != null) {
                    double total = Math.max(1D, value.deadlineNanos - value.startedNanos);
                    double remaining = Math.max(0D, value.deadlineNanos - now);
                    value.bar.setProgress(Math.max(0D, Math.min(1D, remaining / total)));
                    long seconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds((long) remaining) + 1L);
                    value.bar.setTitle("Teleporting in " + seconds + "s");
                }
            }
        }

        void onMove(Player player, Location to) {
            Pending value = pending.get(player.getUniqueId());
            if (value == null || value.executing) return;
            double threshold = Math.max(0D, getConfig().getDouble("teleport.movement-threshold", 0.01D));
            double dx = to.getX() - value.origin.getX();
            double dy = to.getY() - value.origin.getY();
            double dz = to.getZ() - value.origin.getZ();
            if (dx * dx + dy * dy + dz * dz > threshold * threshold) {
                movementCancelled.increment();
                cancel(player.getUniqueId(), CancelReason.MOVED);
            }
        }

        boolean cancel(UUID id, CancelReason reason) {
            Pending value = pending.get(id);
            if (value == null) return attempts.releaseCurrent(id);
            if (value.executing) return false;
            if (!pending.remove(id, value)) return false;
            attempts.release(id, value.attemptId);
            if (reason == CancelReason.DAMAGED) damageCancelled.increment();
            finishCancelled(value, reason);
            return true;
        }

        private void finishCancelled(Pending value, CancelReason reason) {
            if (value.bar != null) value.bar.removeAll();
            if (!value.result.isDone()) value.result.complete(false);
            fire(new PlexonTravelCancelledEvent(value.player, value.type, reason, value.sourceId));
            if (reason == CancelReason.MOVED) tell(value.player, "Teleport cancelled because you moved.");
            else if (reason == CancelReason.DAMAGED) tell(value.player, "Teleport cancelled because you took damage.");
        }

        private void execute(Pending value) {
            if (pending.get(value.player.getUniqueId()) != value || value.executing) return;
            value.executing = true;
            safeResolver.resolve(value.destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(PlexonTravel.this, () -> {
                if (pending.get(value.player.getUniqueId()) != value) return;
                if (error != null || safe == null) {
                    unsafeRejected.increment();
                    pending.remove(value.player.getUniqueId(), value);
                    attempts.release(value.player.getUniqueId(), value.attemptId);
                    finishCancelled(value, CancelReason.DESTINATION_UNSAFE);
                    return;
                }
                double fee = value.player.hasPermission("plexontravel.fee.bypass") ? 0D : value.policy.fee();
                if (fee > 0D && !economy.withdraw(value.player, fee)) {
                    pending.remove(value.player.getUniqueId(), value);
                    attempts.release(value.player.getUniqueId(), value.attemptId);
                    finishCancelled(value, CancelReason.ECONOMY);
                    tell(value.player, "Unable to charge the teleport fee.");
                    return;
                }
                value.chargedFee = fee;
                UUID id = value.player.getUniqueId();
                internalTeleports.add(id);
                value.player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, teleportError) ->
                    Bukkit.getScheduler().runTask(PlexonTravel.this, () -> {
                        internalTeleports.remove(id);
                        if (pending.get(id) != value || !attempts.owns(id, value.attemptId)) return;
                        pending.remove(id, value);
                        attempts.release(id, value.attemptId);
                        if (value.bar != null) value.bar.removeAll();
                        if (Boolean.TRUE.equals(success) && teleportError == null) {
                            setBack(id, value.origin, "travel:" + value.type.name().toLowerCase(Locale.ROOT));
                            startCooldown(value.player, value.type, value.policy.cooldownSeconds());
                            completed.increment();
                            fire(new PlexonTravelCompleteEvent(value.player, value.type, value.destination.view(), value.sourceId));
                            tell(value.player, "Teleport complete.");
                            value.result.complete(true);
                        } else {
                            refundOnce(value);
                            fire(new PlexonTravelCancelledEvent(value.player, value.type, CancelReason.OTHER, value.sourceId));
                            tell(value.player, "Teleport failed; any fee was refunded.");
                            value.result.complete(false);
                        }
                    }));
            }));
        }

        void directJoinTeleport(Player player, Destination destination) {
            safeResolver.resolve(destination).whenComplete((safe, error) -> Bukkit.getScheduler().runTask(PlexonTravel.this, () -> {
                if (!player.isOnline() || error != null || safe == null) return;
                UUID id = player.getUniqueId();
                internalTeleports.add(id);
                player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) ->
                    Bukkit.getScheduler().runTask(PlexonTravel.this, () -> internalTeleports.remove(id)));
            }));
        }

        private void refundOnce(Pending value) {
            if (value.refunded || value.chargedFee <= 0D) return;
            value.refunded = true;
            economy.deposit(value.player, value.chargedFee);
        }

        boolean isInternal(UUID id) { return internalTeleports.contains(id); }
        boolean isPending(UUID id) { return attempts.isActive(id); }

        TravelStatusView status(UUID id) {
            Pending value = pending.get(id);
            if (value == null) return TravelStatusView.idle();
            return new TravelStatusView(true, value.type, value.sourceId, Math.max(0L, TimeUnit.NANOSECONDS.toMillis(value.deadlineNanos - System.nanoTime())));
        }

        void invalidateWarmupsForReload() {
            for (Pending value : List.copyOf(pending.values())) if (!value.executing) cancel(value.player.getUniqueId(), CancelReason.REPLACED);
        }

        void shutdown() {
            if (ticker != null) ticker.cancel();
            for (Pending value : List.copyOf(pending.values())) {
                if (value.executing) {
                    refundOnce(value);
                    if (value.bar != null) value.bar.removeAll();
                    if (!value.result.isDone()) value.result.complete(false);
                } else cancel(value.player.getUniqueId(), CancelReason.PLUGIN_DISABLED);
            }
            pending.clear();
            attempts.clear();
            internalTeleports.clear();
        }
    }

    private final class SafeResolver {
        private final Set<Material> danger = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH);

        CompletableFuture<Location> resolve(Destination destination) {
            CompletableFuture<Location> future = new CompletableFuture<>();
            if (destination == null || !destination.finite()) { future.complete(null); return future; }
            World world = Bukkit.getWorld(destination.worldId());
            if (world == null) world = Bukkit.getWorld(destination.worldName());
            if (world == null) { future.complete(null); return future; }
            final World targetWorld = world;
            Location target = new Location(world, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                future.complete(resolveNow(targetWorld, target));
            } else {
                world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(PlexonTravel.this, () -> {
                    if (error != null) future.completeExceptionally(error);
                    else future.complete(resolveNow(targetWorld, target));
                }));
            }
            return future;
        }

        private Location resolveNow(World world, Location requested) {
            if (!getConfig().getBoolean("safe-teleport.enabled", true)) return requested;
            if (safe(world, requested)) return requested;
            if (!getConfig().getBoolean("safe-teleport.search-nearby", true)) return null;
            int horizontal = Math.max(0, Math.min(8, getConfig().getInt("safe-teleport.horizontal-radius", 3)));
            int vertical = Math.max(0, Math.min(12, getConfig().getInt("safe-teleport.vertical-radius", 4)));
            int baseX = requested.getBlockX(); int baseY = requested.getBlockY(); int baseZ = requested.getBlockZ();
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
                @SuppressWarnings({"rawtypes", "unchecked"}) RegisteredServiceProvider<?> registration = getServer().getServicesManager().getRegistration((Class) type);
                return registration == null ? null : registration.getProvider();
            } catch (Throwable ignored) { return null; }
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
                getLogger().log(Level.WARNING, "Vault withdrawal failed", failure);
                return false;
            }
        }

        void deposit(Player player, double amount) {
            if (amount <= 0D) return;
            try {
                Object provider = provider();
                if (provider != null) provider.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(provider, player, amount);
            } catch (Throwable failure) { getLogger().log(Level.WARNING, "Vault refund failed", failure); }
        }
    }

    private final class WarpGui implements Listener {
        private final NamespacedKey warpKey = new NamespacedKey(PlexonTravel.this, "warp-id");
        private final NamespacedKey pageKey = new NamespacedKey(PlexonTravel.this, "warp-page");

        void open(Player player, int requestedPage) {
            List<Warp> available = warps.values().stream()
                .filter(Warp::enabled)
                .sorted(Comparator.comparingInt(Warp::sortOrder).thenComparing(Warp::id))
                .toList();
            int pages = Math.max(1, (available.size() + 44) / 45);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            Inventory inventory = Bukkit.createInventory(null, 54, GUI_TITLE);
            int from = page * 45;
            int to = Math.min(available.size(), from + 45);
            for (int i = from; i < to; i++) {
                Warp warp = available.get(i);
                Material material = Material.matchMaterial(warp.icon());
                if (material == null || material.isAir()) material = Material.ENDER_PEARL;
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName("§b" + warp.displayName());
                List<String> lore = new ArrayList<>();
                boolean locked = warp.permissionRequired() && !warp.permission().isBlank() && !player.hasPermission(warp.permission());
                double fee = player.hasPermission("plexontravel.fee.bypass") ? 0D : policy(TravelType.WARP, -1D).fee();
                lore.add("§7" + warp.category());
                lore.add("§8World: " + warp.destination().worldName());
                lore.add(fee > 0D ? String.format(Locale.ROOT, "§6Fee: %.2f", fee) : "§aFree travel");
                lore.add(locked ? "§cLocked: " + warp.permission() : "§aClick to travel");
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(warpKey, PersistentDataType.STRING, warp.id());
                item.setItemMeta(meta);
                inventory.setItem(i - from, item);
            }
            inventory.setItem(49, nav(Material.COMPASS, "§fPage " + (page + 1) + "/" + pages, page));
            if (page > 0) inventory.setItem(45, nav(Material.ARROW, "§fPrevious", page - 1));
            if (page + 1 < pages) inventory.setItem(53, nav(Material.ARROW, "§fNext", page + 1));
            player.openInventory(inventory);
        }

        private ItemStack nav(Material type, String name, int page) {
            ItemStack item = new ItemStack(type); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page); item.setItemMeta(meta); return item;
        }

        @EventHandler
        public void click(InventoryClickEvent event) {
            if (!GUI_TITLE.equals(event.getView().getTitle())) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack item = event.getCurrentItem(); if (item == null || !item.hasItemMeta()) return;
            ItemMeta meta = item.getItemMeta();
            Integer page = meta.getPersistentDataContainer().get(pageKey, PersistentDataType.INTEGER);
            if (page != null) { open(player, page); return; }
            String id = meta.getPersistentDataContainer().get(warpKey, PersistentDataType.STRING);
            if (id == null) return;
            Warp warp = warps.get(id);
            if (warp == null || !warp.enabled()) { tell(player, "Warp is no longer available."); player.closeInventory(); return; }
            if (warp.permissionRequired() && !warp.permission().isBlank() && !player.hasPermission(warp.permission())) { tell(player, "You cannot use that warp."); return; }
            player.closeInventory();
            request(player, TravelType.WARP, warp.destination(), "warp:" + warp.id(), -1D);
        }
    }

    private final class TravelCommands implements CommandExecutor, TabCompleter {
        private final DestructiveConfirmationGate deleteGate = new DestructiveConfirmationGate(15_000L);

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String name = command.getName().toLowerCase(Locale.ROOT);
            if (Set.of("spawn", "hub", "back", "warp", "warps").contains(name) && !standardCommandsEnabled()) {
                tell(sender, "Standard commands are in staging-safe mode. Use /ptravel ... or enable migration.claim-standard-commands after the source audit.");
                return true;
            }
            return switch (name) {
                case "spawn" -> playerCommand(sender, p -> request(p, TravelType.SPAWN, spawn.get(), "spawn", -1D));
                case "hub" -> playerCommand(sender, p -> request(p, TravelType.HUB, resolvedHub(), "hub", -1D));
                case "back" -> playerCommand(sender, this::backCommand);
                case "warp" -> playerCommand(sender, p -> warpCommand(p, args));
                case "warps" -> playerCommand(sender, p -> { warpGui.open(p, 0); return true; });
                case "setspawn" -> playerCommand(sender, p -> setDestination(p, "spawn"));
                case "sethub" -> playerCommand(sender, p -> setDestination(p, "hub"));
                case "setwarp" -> playerCommand(sender, p -> setWarp(p, args));
                case "delwarp" -> deleteWarp(sender, args);
                case "renamewarp" -> renameWarp(sender, args);
                case "ptravel" -> ptravel(sender, args);
                case "traveladmin" -> admin(sender, args);
                default -> false;
            };
        }

        private boolean playerCommand(CommandSender sender, PlayerAction action) {
            if (!(sender instanceof Player player)) { tell(sender, "This command requires a player."); return true; }
            return action.run(player);
        }

        private boolean backCommand(Player player) {
            BackEntry entry = back.get(player.getUniqueId());
            if (entry == null) { tell(player, "No back location is available."); return true; }
            request(player, TravelType.BACK, entry.destination(), "back:" + entry.source(), -1D); return true;
        }

        private boolean warpCommand(Player player, String[] args) {
            if (args.length == 0) { warpGui.open(player, 0); return true; }
            Warp warp = warps.get(normalizeId(args[0]));
            if (warp == null || !warp.enabled()) { tell(player, "Warp not found."); return true; }
            if (warp.permissionRequired() && !warp.permission().isBlank() && !player.hasPermission(warp.permission())) { tell(player, "You cannot use that warp."); return true; }
            request(player, TravelType.WARP, warp.destination(), "warp:" + warp.id(), -1D); return true;
        }

        private boolean setDestination(Player player, String id) {
            if (!player.hasPermission("plexontravel.admin.destinations")) { tell(player, "No permission."); return true; }
            Destination destination = Destination.from(player.getLocation());
            if (id.equals("spawn")) spawn.set(destination); else hub.set(destination);
            storage.saveDestinationAsync(id, destination);
            tell(player, Character.toUpperCase(id.charAt(0)) + id.substring(1) + " updated.");
            return true;
        }

        private boolean setWarp(Player player, String[] args) {
            if (!player.hasPermission("plexontravel.admin.warps")) { tell(player, "No permission."); return true; }
            if (args.length < 1) { tell(player, "Usage: /setwarp <name>"); return true; }
            String id = normalizeId(args[0]);
            if (id.isBlank()) { tell(player, "Invalid warp name."); return true; }
            Warp previous = warps.get(id);
            long revision = previous == null ? 1L : previous.revision() + 1L;
            Warp current = new Warp(id, args[0], Destination.from(player.getLocation()), true,
                "plexontravel.warp." + id, false, previous == null ? warps.size() : previous.sortOrder(),
                previous == null ? "ENDER_PEARL" : previous.icon(), previous == null ? "Server" : previous.category(), revision);
            warps.put(id, current); storage.saveWarpAsync(current);
            if (previous == null) fire(new PlexonWarpCreatedEvent(current.view())); else fire(new PlexonWarpUpdatedEvent(previous.view(), current.view()));
            tell(player, "Warp '" + id + "' saved."); return true;
        }

        private boolean deleteWarp(CommandSender sender, String[] args) {
            if (!sender.hasPermission("plexontravel.admin.warps")) { tell(sender, "No permission."); return true; }
            if (args.length < 1) { tell(sender, "Usage: /delwarp <id>"); return true; }
            String id = normalizeId(args[0]);
            Warp current = warps.get(id);
            if (current == null) { tell(sender, "Warp not found."); return true; }
            String actor = sender instanceof Player player ? "player:" + player.getUniqueId() : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
            DestructiveConfirmationGate.Decision decision = deleteGate.check(actor, "delete-warp", id, current.revision(), System.currentTimeMillis());
            if (decision == DestructiveConfirmationGate.Decision.ARMED) {
                tell(sender, "Run /delwarp " + id + " again within 15s to confirm deletion of revision " + current.revision() + ".");
                return true;
            }
            if (!warps.remove(id, current)) { tell(sender, "Warp changed before deletion; confirmation invalidated."); return true; }
            deleteGate.invalidate("delete-warp", id);
            storage.deleteWarpAsync(current.id());
            fire(new PlexonWarpDeletedEvent(current.view()));
            tell(sender, "Warp '" + id + "' deleted.");
            return true;
        }

        private boolean renameWarp(CommandSender sender, String[] args) {
            if (!sender.hasPermission("plexontravel.admin.warps")) { tell(sender, "No permission."); return true; }
            if (args.length < 2) { tell(sender, "Usage: /renamewarp <id> <display name...>"); return true; }
            String id = normalizeId(args[0]);
            Warp old = warps.get(id);
            String displayName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            if (old == null || displayName.isBlank() || displayName.length() > 80) { tell(sender, "Rename cannot be completed."); return true; }
            Warp renamed = new Warp(old.id(), displayName, old.destination(), old.enabled(), old.permission(),
                old.permissionRequired(), old.sortOrder(), old.icon(), old.category(), old.revision() + 1L);
            warps.put(id, renamed);
            deleteGate.invalidate("delete-warp", id);
            storage.saveWarpAsync(renamed);
            fire(new PlexonWarpUpdatedEvent(old.view(), renamed.view()));
            tell(sender, "Warp display name updated; stable ID remains '" + id + "'.");
            return true;
        }

        private boolean ptravel(CommandSender sender, String[] args) {
            if (args.length == 0) { tell(sender, "Usage: /ptravel <spawn|hub|back|warp|warps>"); return true; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            return switch (sub) {
                case "spawn" -> playerCommand(sender, p -> request(p, TravelType.SPAWN, spawn.get(), "spawn", -1D));
                case "hub" -> playerCommand(sender, p -> request(p, TravelType.HUB, resolvedHub(), "hub", -1D));
                case "back" -> playerCommand(sender, this::backCommand);
                case "warp" -> playerCommand(sender, p -> warpCommand(p, rest));
                case "warps" -> playerCommand(sender, p -> { warpGui.open(p, 0); return true; });
                default -> { tell(sender, "Unknown travel subcommand."); yield true; }
            };
        }

        private boolean admin(CommandSender sender, String[] args) {
            if (!sender.hasPermission("plexontravel.admin")) { tell(sender, "No permission."); return true; }
            if (args.length == 0) { tell(sender, "Usage: /traveladmin <reload|diagnostics|backup|migrate>"); return true; }
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> { reloadValidated(sender); yield true; }
                case "diagnostics" -> { diagnostics(sender); yield true; }
                case "backup" -> { backup(sender); yield true; }
                case "migrate" -> { migrate(sender, java.util.Arrays.copyOfRange(args, 1, args.length)); yield true; }
                case "setspawn" -> playerCommand(sender, p -> setDestination(p, "spawn"));
                case "sethub" -> playerCommand(sender, p -> setDestination(p, "hub"));
                default -> { tell(sender, "Unknown admin subcommand."); yield true; }
            };
        }

        private void reloadValidated(CommandSender sender) {
            File file = new File(getDataFolder(), "config.yml");
            YamlConfiguration candidate = YamlConfiguration.loadConfiguration(file);
            List<String> errors = TravelConfigValidator.validate(candidate);
            if (!errors.isEmpty()) {
                tell(sender, "Reload rejected; previous known-good configuration retained: " + String.join("; ", errors));
                return;
            }
            reloadConfig();
            runtimeEpoch++;
            travel.invalidateWarmupsForReload();
            tell(sender, "Configuration validated and atomically activated; runtime epoch " + runtimeEpoch + ".");
        }

        private void diagnostics(CommandSender sender) {
            tell(sender, "Core " + core.version().pluginVersion() + " / API " + core.version().apiVersion());
            tell(sender, "spawn=" + (spawn.get() != null) + ", hubMode=" + getConfig().getString("hub.mode", "SEPARATE") + ", warps=" + warps.size());
            tell(sender, "pending=" + travel.attempts.size() + ", started=" + travel.started.sum() + ", complete=" + travel.completed.sum());
            tell(sender, "cancelled[moved=" + travel.movementCancelled.sum() + ", damage=" + travel.damageCancelled.sum() + ", unsafe=" + travel.unsafeRejected.sum() + "]");
            tell(sender, "backCached=" + back.size() + ", standardCommands=" + standardCommandsEnabled() + ", runtimeEpoch=" + runtimeEpoch);
        }

        private void backup(CommandSender sender) {
            try {
                Path source = storage.path();
                Path target = getDataFolder().toPath().resolve("travel-backup-" + System.currentTimeMillis() + ".db");
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                tell(sender, "Backup created: " + target.getFileName());
            } catch (IOException failure) { tell(sender, "Backup failed: " + failure.getMessage()); }
        }

        private void migrate(CommandSender sender, String[] args) {
            String action = args.length == 0 ? "scan" : args[0].toLowerCase(Locale.ROOT);
            if (action.equals("scan") || action.equals("plan") || action.equals("status")) {
                Path plugins = getDataFolder().toPath().getParent();
                Path essentials = plugins == null ? Path.of("plugins", "Essentials", "warps") : plugins.resolve("Essentials").resolve("warps");
                long count = 0;
                if (Files.isDirectory(essentials)) {
                    try (var stream = Files.list(essentials)) { count = stream.filter(p -> p.getFileName().toString().endsWith(".yml")).count(); }
                    catch (IOException ignored) {}
                }
                tell(sender, "Migration scan: Essentials warps=" + count + "; current PlexonTravel warps=" + warps.size() + ". Source remains read-only.");
                return;
            }
            if (action.equals("execute")) {
                if (!getConfig().getBoolean("migration.allow-execute", false)) { tell(sender, "Migration execute is disabled. Enable migration.allow-execute only on staging after scan/backup."); return; }
                importEssentialsWarps(sender); return;
            }
            tell(sender, "Usage: /traveladmin migrate <scan|plan|execute|status>");
        }

        private void importEssentialsWarps(CommandSender sender) {
            Path plugins = getDataFolder().toPath().getParent();
            Path folder = plugins == null ? Path.of("plugins", "Essentials", "warps") : plugins.resolve("Essentials").resolve("warps");
            if (!Files.isDirectory(folder)) { tell(sender, "Essentials warp folder not found."); return; }
            int imported = 0, skipped = 0, unresolved = 0;
            try (var stream = Files.list(folder)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                    String filename = file.getFileName().toString();
                    String id = normalizeId(filename.substring(0, filename.length() - 4));
                    if (id.isBlank() || warps.containsKey(id)) { skipped++; continue; }
                    YamlConfiguration source = YamlConfiguration.loadConfiguration(file.toFile());
                    String worldName = source.getString("world", ""); World world = Bukkit.getWorld(worldName);
                    if (world == null) { unresolved++; continue; }
                    Destination destination = new Destination(world.getUID(), world.getName(), source.getDouble("x"), source.getDouble("y"),
                        source.getDouble("z"), (float) source.getDouble("yaw"), (float) source.getDouble("pitch"));
                    if (!destination.finite()) { unresolved++; continue; }
                    Warp warp = new Warp(id, id, destination, true, "plexontravel.warp." + id, false, warps.size(), "ENDER_PEARL", "Imported", 1L);
                    warps.put(id, warp); storage.saveWarpAsync(warp); fire(new PlexonWarpCreatedEvent(warp.view())); imported++;
                }
            } catch (IOException failure) { tell(sender, "Migration failed: " + failure.getMessage()); return; }
            tell(sender, "Migration complete: imported=" + imported + ", skipped=" + skipped + ", unresolved=" + unresolved + ". Source files were not modified.");
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            String name = command.getName().toLowerCase(Locale.ROOT);
            if (name.equals("warp") && args.length == 1) return prefix(warps.keySet(), args[0]);
            if ((name.equals("delwarp") || name.equals("renamewarp")) && args.length == 1) return prefix(warps.keySet(), args[0]);
            if (name.equals("ptravel") && args.length == 1) return prefix(List.of("spawn", "hub", "back", "warp", "warps"), args[0]);
            if (name.equals("ptravel") && args.length == 2 && args[0].equalsIgnoreCase("warp")) return prefix(warps.keySet(), args[1]);
            if (name.equals("traveladmin") && args.length == 1) return prefix(List.of("reload", "diagnostics", "backup", "migrate", "setspawn", "sethub"), args[0]);
            if (name.equals("traveladmin") && args.length == 2 && args[0].equalsIgnoreCase("migrate")) return prefix(List.of("scan", "plan", "execute", "status"), args[1]);
            return List.of();
        }

        private List<String> prefix(Iterable<String> values, String input) {
            String lower = input.toLowerCase(Locale.ROOT); List<String> out = new ArrayList<>();
            for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
            out.sort(String::compareToIgnoreCase); return out;
        }
    }

    private final class PublicApi implements PlexonTravelAPI {
        @Override public Optional<TravelDestinationView> spawn() { return Optional.ofNullable(PlexonTravel.this.spawn.get()).map(Destination::view); }
        @Override public Optional<TravelDestinationView> hub() { return Optional.ofNullable(resolvedHub()).map(Destination::view); }
        @Override public Optional<WarpView> warp(String id) { return Optional.ofNullable(warps.get(normalizeId(id))).map(Warp::view); }
        @Override public List<WarpView> warps() { return PlexonTravel.this.warps.values().stream().sorted(Comparator.comparingInt(Warp::sortOrder).thenComparing(Warp::id)).map(Warp::view).toList(); }
        @Override public Optional<BackLocationView> back(UUID playerId) { return Optional.ofNullable(PlexonTravel.this.back.get(playerId)).map(BackEntry::view); }
        @Override public TravelStatusView status(UUID playerId) { return travel.status(playerId); }
        @Override public boolean isPending(UUID playerId) { return travel.isPending(playerId); }
        @Override public boolean cancelPending(UUID playerId) {
            if (Bukkit.isPrimaryThread()) return travel.cancel(playerId, CancelReason.OTHER);
            Bukkit.getScheduler().runTask(PlexonTravel.this, () -> travel.cancel(playerId, CancelReason.OTHER)); return true;
        }
        @Override public CompletableFuture<Boolean> teleport(UUID playerId, TravelDestinationView destination, TravelType type) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Runnable action = () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) { result.complete(false); return; }
                travel.request(player, type == null ? TravelType.ADMIN : type, Destination.from(destination), "api", policy(type == null ? TravelType.ADMIN : type, -1D))
                    .whenComplete((ok, error) -> { if (error != null) result.completeExceptionally(error); else result.complete(ok); });
            };
            if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(PlexonTravel.this, action);
            return result;
        }
    }

    private static String normalizeId(String input) {
        if (input == null) return "";
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private record Destination(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
        static Destination from(Location location) { return new Destination(location.getWorld().getUID(), location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()); }
        static Destination from(TravelDestinationView view) { return new Destination(view.worldId(), view.worldName(), view.x(), view.y(), view.z(), view.yaw(), view.pitch()); }
        static boolean finite(Location l) { return Double.isFinite(l.getX()) && Double.isFinite(l.getY()) && Double.isFinite(l.getZ()); }
        boolean finite() { return worldId != null && worldName != null && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw) && Float.isFinite(pitch); }
        Location toLocation() { World world = Bukkit.getWorld(worldId); if (world == null) world = Bukkit.getWorld(worldName); return world == null ? null : new Location(world, x, y, z, yaw, pitch); }
        TravelDestinationView view() { return new TravelDestinationView(worldId, worldName, x, y, z, yaw, pitch); }
    }

    private record Warp(String id, String displayName, Destination destination, boolean enabled, String permission,
                        boolean permissionRequired, int sortOrder, String icon, String category, long revision) {
        WarpView view() { return new WarpView(id, displayName, destination.view(), enabled, permission, sortOrder, icon, category, revision); }
    }

    private record BackEntry(Destination destination, String source, long updatedAt) {
        BackLocationView view() { return new BackLocationView(destination.view(), source, updatedAt); }
    }

    private record Policy(int warmupSeconds, int cooldownSeconds, double fee) {}

    private static final class Pending {
        final Player player; final TravelType type; final Destination destination; final String sourceId; final Policy policy;
        final Location origin; final long startedNanos; final long deadlineNanos; final long epoch; final long attemptId; final CompletableFuture<Boolean> result; final BossBar bar;
        boolean executing; double chargedFee; boolean refunded;
        Pending(Player player, TravelType type, Destination destination, String sourceId, Policy policy, Location origin,
                long startedNanos, long deadlineNanos, long epoch, long attemptId, CompletableFuture<Boolean> result, BossBar bar) {
            this.player = player; this.type = type; this.destination = destination; this.sourceId = sourceId; this.policy = policy;
            this.origin = origin; this.startedNanos = startedNanos; this.deadlineNanos = deadlineNanos; this.epoch = epoch; this.attemptId = attemptId; this.result = result; this.bar = bar;
        }
    }

    @FunctionalInterface private interface PlayerAction { boolean run(Player player); }

    private final class Storage implements AutoCloseable {
        private final Path databasePath;
        private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PlexonTravel-SQLite"); t.setDaemon(true); return t; });
        private Connection connection;
        Storage(Path path) { this.databasePath = path; }
        Path path() { return databasePath; }

        synchronized void open() throws Exception {
            Files.createDirectories(databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("CREATE TABLE IF NOT EXISTS destinations(id TEXT PRIMARY KEY, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, updated_at INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS warps(id TEXT PRIMARY KEY, display_name TEXT NOT NULL, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, enabled INTEGER NOT NULL, permission TEXT NOT NULL, permission_required INTEGER NOT NULL, sort_order INTEGER NOT NULL, icon TEXT NOT NULL, category TEXT NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS back_locations(player_uuid TEXT PRIMARY KEY, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, source TEXT NOT NULL, updated_at INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS migration_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
        }

        synchronized Optional<Destination> loadDestination(String id) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM destinations WHERE id=?")) {
                ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(destination(rs)) : Optional.empty(); }
            }
        }

        synchronized Map<String, Warp> loadWarps() throws SQLException {
            Map<String, Warp> result = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM warps")) {
                while (rs.next()) {
                    Destination d = destination(rs);
                    Warp w = new Warp(rs.getString("id"), rs.getString("display_name"), d, rs.getInt("enabled") != 0,
                        rs.getString("permission"), rs.getInt("permission_required") != 0, rs.getInt("sort_order"),
                        rs.getString("icon"), rs.getString("category"), rs.getLong("revision"));
                    result.put(w.id(), w);
                }
            }
            return result;
        }

        synchronized Map<UUID, BackEntry> loadBack() throws SQLException {
            Map<UUID, BackEntry> result = new HashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM back_locations")) {
                while (rs.next()) result.put(UUID.fromString(rs.getString("player_uuid")), new BackEntry(destination(rs), rs.getString("source"), rs.getLong("updated_at")));
            }
            return result;
        }

        private Destination destination(ResultSet rs) throws SQLException {
            return new Destination(UUID.fromString(rs.getString("world_uuid")), rs.getString("world_name"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));
        }

        void saveDestinationAsync(String id, Destination d) { submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO destinations(id,world_uuid,world_name,x,y,z,yaw,pitch,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,updated_at=excluded.updated_at")) {
                ps.setString(1,id); bindDestination(ps,d,2); ps.setLong(9,System.currentTimeMillis()); ps.executeUpdate();
            }
        }); }

        void saveWarpAsync(Warp w) { submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO warps(id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,enabled,permission,permission_required,sort_order,icon,category,revision,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,enabled=excluded.enabled,permission=excluded.permission,permission_required=excluded.permission_required,sort_order=excluded.sort_order,icon=excluded.icon,category=excluded.category,revision=excluded.revision,updated_at=excluded.updated_at")) {
                ps.setString(1,w.id()); ps.setString(2,w.displayName()); bindDestination(ps,w.destination(),3); ps.setInt(10,w.enabled()?1:0); ps.setString(11,w.permission()); ps.setInt(12,w.permissionRequired()?1:0); ps.setInt(13,w.sortOrder()); ps.setString(14,w.icon()); ps.setString(15,w.category()); ps.setLong(16,w.revision()); ps.setLong(17,System.currentTimeMillis()); ps.executeUpdate();
            }
        }); }

        void deleteWarpAsync(String id) { submit(() -> { try (PreparedStatement ps = connection.prepareStatement("DELETE FROM warps WHERE id=?")) { ps.setString(1,id); ps.executeUpdate(); } }); }

        void saveBackAsync(UUID player, BackEntry e) { submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO back_locations(player_uuid,world_uuid,world_name,x,y,z,yaw,pitch,source,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,source=excluded.source,updated_at=excluded.updated_at")) {
                ps.setString(1,player.toString()); bindDestination(ps,e.destination(),2); ps.setString(9,e.source()); ps.setLong(10,e.updatedAt()); ps.executeUpdate();
            }
        }); }

        private void bindDestination(PreparedStatement ps, Destination d, int index) throws SQLException {
            ps.setString(index,d.worldId().toString()); ps.setString(index+1,d.worldName()); ps.setDouble(index+2,d.x()); ps.setDouble(index+3,d.y()); ps.setDouble(index+4,d.z()); ps.setFloat(index+5,d.yaw()); ps.setFloat(index+6,d.pitch());
        }

        private void submit(SqlWork work) {
            io.execute(() -> { try { synchronized (this) { work.run(); } } catch (Exception failure) { getLogger().log(Level.SEVERE, "Travel persistence write failed", failure); } });
        }

        @Override public void close() {
            io.shutdown();
            try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            synchronized (this) { if (connection != null) try { connection.close(); } catch (SQLException ignored) {} }
        }
    }

    @FunctionalInterface private interface SqlWork { void run() throws Exception; }
}
