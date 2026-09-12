package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI;
import com.plexon.travel.api.PlexonTravelAPI.BackLocationView;
import com.plexon.travel.api.PlexonTravelAPI.CancelReason;
import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import com.plexon.travel.api.PlexonTravelAPI.TravelStatusView;
import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import com.plexon.travel.api.PlexonTravelAPI.WarpView;
import com.plexon.travel.internal.TravelConfigValidator;
import com.plexon.travel.papi.PlexonTravelExpansion;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class PlexonTravel extends JavaPlugin implements Listener {
    private static final String MODULE_ID = "travel";
    private static final ModuleVersionRange CORE_RANGE = ModuleVersionRange.parse(">=2.0 <3.0");

    private PlexonCoreAPI core;
    private TravelStorage storage;
    private DestinationRegistry destinations;
    private TravelMessages messages;
    private TravelEngine engine;
    private RtpService rtp;
    private TpaService tpa;
    private TravelMenus menus;
    private TravelCommands commands;
    private PublicApi publicApi;
    private boolean startupFailed;
    private String startupPhase = "bootstrap";

    @Override
    public void onEnable() {
        startupFailed = false;
        try {
            markStartupPhase("config");
            saveDefaultConfig();
            saveResourceIfAbsent("messages.yml");
            saveResourceIfAbsent("gui.yml");
            saveResourceIfAbsent("migration.yml");

            List<String> startupErrors = TravelConfigValidator.validate(getConfig());
            if (!startupErrors.isEmpty()) {
                failStartup("Invalid PlexonTravel configuration: " + String.join("; ", startupErrors), null);
                return;
            }

            markStartupPhase("core");
            RegisteredServiceProvider<PlexonCoreAPI> registration = getServer().getServicesManager().getRegistration(PlexonCoreAPI.class);
            if (registration == null || registration.getProvider() == null) {
                failStartup("PlexonCore API service is unavailable; PlexonTravel cannot start.", null);
                return;
            }
            core = registration.getProvider();
            if (!CORE_RANGE.contains(core.version())) {
                failStartup("PlexonCore API " + core.version().apiVersion() + " is outside supported range >=2.0 <3.0", null);
                return;
            }

            ModuleRegistry.RegistrationResult moduleRegistration = core.modules().register(new ModuleDescriptor(
                MODULE_ID,
                "PlexonTravel",
                getName(),
                getPluginMeta().getVersion(),
                this,
                CORE_RANGE,
                Set.of("spawn", "hub", "back", "warps", "per-world-destinations", "tpa", "rtp", "safe-teleport",
                    "teleport-warmup", "minimessage-ui", "travel-api", "travel-events", "sqlite-persistence"),
                ModuleState.STARTING,
                "Initializing travel runtime",
                Instant.now()
            ));
            if (!moduleRegistration.success()) {
                failStartup("Core module registration failed: " + moduleRegistration.message(), null);
                return;
            }

            markStartupPhase("storage");
            storage = new TravelStorage(this, getDataFolder().toPath().resolve("travel.db"));
            storage.open();
            destinations = new DestinationRegistry(this, storage);
            destinations.load();

            markStartupPhase("runtime");
            messages = new TravelMessages(this);
            engine = new TravelEngine(this, destinations, messages);
            rtp = new RtpService(this, engine, messages);
            tpa = new TpaService(this, engine, messages);
            menus = new TravelMenus(this, destinations, engine, messages, rtp);
            commands = new TravelCommands(this, destinations, engine, messages, menus, tpa, rtp, storage);
            publicApi = new PublicApi();

            markStartupPhase("bukkit-registration");
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
            getServer().getPluginManager().registerEvents(menus, this);
            registerCommands();
            engine.startTicker();
            tpa.startTicker();

            core.modules().updateState(MODULE_ID, this, ModuleState.READY,
                "Core " + core.version().pluginVersion() + " / API " + core.version().apiVersion()
                    + "; per-world destinations=" + (destinations.worldSpawnCount() + destinations.worldHubCount())
                    + "; warps=" + destinations.warpCount());
            clearStartupFailureReport();
            getLogger().info("STARTUP_READY version=" + getPluginMeta().getVersion()
                + " core=" + core.version().pluginVersion() + " warps=" + destinations.warpCount());
            getLogger().info("PlexonTravel " + getPluginMeta().getVersion() + " enabled against PlexonCore " + core.version().pluginVersion());
        } catch (Exception | LinkageError failure) {
            failStartup("Unexpected startup failure during " + startupPhase + ": " + failure.getMessage(), failure);
        }
    }

    private void markStartupPhase(String phase) {
        startupPhase = phase;
        getLogger().info("STARTUP_PHASE=" + phase);
    }

    private void failStartup(String detail, Throwable failure) {
        startupFailed = true;
        if (core != null) {
            try { core.modules().updateState(MODULE_ID, this, ModuleState.FAILED, detail); } catch (Throwable ignored) { }
        }
        if (failure == null) getLogger().severe(detail);
        else getLogger().log(Level.SEVERE, detail, failure);
        writeStartupFailureReport(detail, failure);
        getServer().getPluginManager().disablePlugin(this);
    }

    private void writeStartupFailureReport(String detail, Throwable failure) {
        try {
            Path report = startupFailureReport();
            Files.createDirectories(report.getParent());
            Throwable root = rootCause(failure);
            String exceptionType = root == null ? "ConfigurationValidationFailure" : root.getClass().getName();
            String rootMessage = root == null ? detail : root.getMessage();
            Files.writeString(report, String.join("\n",
                "plugin_version=" + cleanLine(getPluginMeta().getVersion()),
                "paper_version=" + cleanLine(getServer().getVersion()),
                "java_version=" + cleanLine(System.getProperty("java.version", "unknown")),
                "startup_phase=" + cleanLine(startupPhase),
                "exception_type=" + cleanLine(exceptionType),
                "root_cause_message=" + cleanLine(rootMessage == null ? detail : rootMessage),
                "timestamp=" + Instant.now(),
                ""));
        } catch (Exception reportFailure) {
            getLogger().log(Level.WARNING, "Could not write startup-failure.txt", reportFailure);
        }
    }

    private void clearStartupFailureReport() {
        try {
            Files.deleteIfExists(startupFailureReport());
        } catch (Exception failure) {
            getLogger().log(Level.WARNING, "Could not clear stale startup-failure.txt", failure);
        }
    }

    private Path startupFailureReport() {
        return getDataFolder().toPath().resolve("startup-failure.txt");
    }

    private Throwable rootCause(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null && cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor;
    }

    private String cleanLine(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return clean.length() <= 512 ? clean : clean.substring(0, 512);
    }

    @Override
    public void onDisable() {
        if (tpa != null) tpa.shutdown();
        if (rtp != null) rtp.shutdown();
        if (engine != null) engine.shutdown();
        if (getServer() != null) getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (!startupFailed) {
                try { core.modules().updateState(MODULE_ID, this, ModuleState.DISABLED, "Plugin disabled"); } catch (Throwable ignored) { }
            }
            try { core.modules().unregisterOwnedBy(this); } catch (Throwable ignored) { }
        }
        if (storage != null) storage.close();
    }

    private void registerCommands() {
        for (String name : List.of("spawn", "hub", "back", "warp", "warps", "travel", "rtp", "tpa", "tpahere",
            "tpaccept", "tpdeny", "tpcancel", "setspawn", "sethub", "setwarp", "delwarp", "renamewarp", "ptravel", "traveladmin")) {
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

    boolean requestSpawn(Player player) {
        if (!requirePermission(player, "plexontravel.spawn")) return true;
        Destination destination = destinations.spawnFor(player.getWorld());
        return request(player, TravelType.SPAWN, destination, "spawn:" + player.getWorld().getUID(), -1D);
    }

    boolean requestHub(Player player) {
        if (!requirePermission(player, "plexontravel.hub")) return true;
        Destination destination = destinations.hubFor(player.getWorld());
        return request(player, TravelType.HUB, destination, "hub:" + player.getWorld().getUID(), -1D);
    }

    boolean requestBack(Player player) {
        if (!requirePermission(player, "plexontravel.back")) return true;
        BackEntry entry = destinations.back(player.getUniqueId());
        if (entry == null) {
            messages.send(player, "back.missing", "<yellow>No back location is available.</yellow>");
            return true;
        }
        return request(player, TravelType.BACK, entry.destination(), "back:" + entry.source(), -1D);
    }

    boolean requestWarp(Player player, String requestedId) {
        if (!requirePermission(player, "plexontravel.warp")) return true;
        Warp warp = destinations.warp(requestedId);
        if (warp == null || !warp.enabled()) {
            messages.send(player, "warp.missing", "<red>Warp not found.</red>");
            return true;
        }
        if (warp.permissionRequired() && !warp.permission().isBlank() && !player.hasPermission(warp.permission())) {
            messages.send(player, "commands.no-permission", "<red>You do not have permission.</red>");
            return true;
        }
        return request(player, TravelType.WARP, warp.destination(), "warp:" + warp.id(), -1D);
    }

    private boolean requirePermission(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        messages.send(player, "commands.no-permission", "<red>You do not have permission.</red>");
        return false;
    }

    private boolean request(Player player, TravelType type, Destination destination, String sourceId, double feeOverride) {
        if (destination == null) {
            messages.send(player, "destination.missing", "<red>That destination is not configured.</red>");
            return true;
        }
        engine.request(player, type, destination, sourceId, feeOverride);
        return true;
    }

    PlexonCoreAPI core() { return core; }
    DestinationRegistry destinations() { return destinations; }
    TravelEngine engine() { return engine; }
    RtpService rtp() { return rtp; }
    TpaService tpa() { return tpa; }
    TravelMessages messages() { return messages; }
    void fire(Event event) { getServer().getPluginManager().callEvent(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (engine != null && event.getTo() != null) engine.onMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (engine != null && event.getEntity() instanceof Player player) engine.cancel(player.getUniqueId(), CancelReason.DAMAGED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (engine != null) engine.cancel(playerId, CancelReason.QUIT);
        if (tpa != null) tpa.onQuit(playerId);
        if (rtp != null) rtp.cancel(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        if (engine != null) engine.cancel(playerId, CancelReason.DIED);
        if (rtp != null) rtp.cancel(playerId);
        if (destinations != null && getConfig().getBoolean("back.capture.deaths", true)) {
            destinations.setBack(playerId, player.getLocation(), "death");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (engine == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        if (engine.isInternal(playerId)) return;
        if (rtp != null) rtp.cancel(playerId);
        engine.cancel(playerId, CancelReason.WORLD_CHANGED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (engine == null || destinations == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        if (engine.isInternal(playerId)) return;
        engine.cancel(playerId, CancelReason.REPLACED);
        if (!getConfig().getBoolean("back.capture.external-teleports", true)) return;
        boolean portal = event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL;
        if (!portal || getConfig().getBoolean("back.capture.portals", false)) {
            destinations.setBack(playerId, event.getFrom(), "external:" + event.getCause().name().toLowerCase(Locale.ROOT));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (destinations == null) return;
        String mode = getConfig().getString("respawn.mode", "VANILLA").toUpperCase(Locale.ROOT);
        Destination destination = null;
        if (mode.equals("SPAWN")) destination = destinations.spawnFor(event.getPlayer().getWorld());
        else if (mode.equals("HUB")) destination = destinations.hubFor(event.getPlayer().getWorld());
        else if (mode.equals("LAST_BED_OR_SPAWN") && !event.isBedSpawn() && !event.isAnchorSpawn()) {
            destination = destinations.spawnFor(event.getPlayer().getWorld());
        }
        if (destination == null) return;
        Location location = destination.toLocation();
        if (location != null) event.setRespawnLocation(location);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (destinations == null || engine == null || !getConfig().getBoolean("join.teleport-to-spawn", false)) return;
        Destination destination = destinations.spawnFor(event.getPlayer().getWorld());
        if (destination == null) return;
        Bukkit.getScheduler().runTask(this, () -> engine.directJoinTeleport(event.getPlayer(), destination));
    }

    private final class PublicApi implements PlexonTravelAPI {
        @Override
        public Optional<TravelDestinationView> spawn() {
            return Optional.ofNullable(destinations.legacySpawn()).map(Destination::view);
        }

        @Override
        public Optional<TravelDestinationView> spawn(UUID worldId) {
            World world = Bukkit.getWorld(worldId);
            return Optional.ofNullable(world == null ? null : destinations.spawnFor(world)).map(Destination::view);
        }

        @Override
        public Optional<TravelDestinationView> hub() {
            return Optional.ofNullable(destinations.legacyHub()).map(Destination::view);
        }

        @Override
        public Optional<TravelDestinationView> hub(UUID worldId) {
            World world = Bukkit.getWorld(worldId);
            return Optional.ofNullable(world == null ? null : destinations.hubFor(world)).map(Destination::view);
        }

        @Override
        public Optional<WarpView> warp(String id) {
            return Optional.ofNullable(destinations.warp(id)).map(Warp::view);
        }

        @Override
        public List<WarpView> warps() {
            return destinations.warps().stream().map(Warp::view).toList();
        }

        @Override public int warpCount() { return destinations.warpCount(); }

        @Override
        public Optional<BackLocationView> back(UUID playerId) {
            return Optional.ofNullable(destinations.back(playerId)).map(BackEntry::view);
        }

        @Override public TravelStatusView status(UUID playerId) { return engine.status(playerId); }
        @Override public boolean isPending(UUID playerId) { return engine.isPending(playerId); }

        @Override
        public boolean cancelPending(UUID playerId) {
            if (Bukkit.isPrimaryThread()) return engine.cancel(playerId, CancelReason.OTHER);
            Bukkit.getScheduler().runTask(PlexonTravel.this, () -> engine.cancel(playerId, CancelReason.OTHER));
            return true;
        }

        @Override
        public CompletableFuture<Boolean> teleport(UUID playerId, TravelDestinationView destination, TravelType type) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Runnable action = () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    result.complete(false);
                    return;
                }
                TravelType resolvedType = type == null ? TravelType.ADMIN : type;
                engine.request(player, resolvedType, Destination.from(destination), "api", engine.policy(resolvedType, -1D))
                    .whenComplete((ok, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(ok);
                    });
            };
            if (Bukkit.isPrimaryThread()) action.run();
            else Bukkit.getScheduler().runTask(PlexonTravel.this, action);
            return result;
        }
    }
}
