package com.plexon.travel;

import com.plexon.travel.event.PlexonWarpCreatedEvent;
import com.plexon.travel.event.PlexonWarpDeletedEvent;
import com.plexon.travel.event.PlexonWarpUpdatedEvent;
import com.plexon.travel.internal.DestructiveConfirmationGate;
import com.plexon.travel.internal.TravelConfigValidator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TravelCommands implements CommandExecutor, TabCompleter {
    private static final Set<String> TAKEOVER_COMMANDS = Set.of(
        "spawn", "hub", "back", "warp", "warps", "rtp", "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel"
    );

    private final PlexonTravel plugin;
    private final DestinationRegistry destinations;
    private final TravelEngine engine;
    private final TravelMessages messages;
    private final TravelMenus menus;
    private final TpaService tpa;
    private final RtpService rtp;
    private final TravelStorage storage;
    private final TravelWorldAdmin worldAdmin;
    private final DestructiveConfirmationGate deleteGate = new DestructiveConfirmationGate(15_000L);

    TravelCommands(PlexonTravel plugin, DestinationRegistry destinations, TravelEngine engine, TravelMessages messages,
                   TravelMenus menus, TpaService tpa, RtpService rtp, TravelStorage storage) {
        this.plugin = plugin;
        this.destinations = destinations;
        this.engine = engine;
        this.messages = messages;
        this.menus = menus;
        this.tpa = tpa;
        this.rtp = rtp;
        this.storage = storage;
        this.worldAdmin = new TravelWorldAdmin(plugin, messages, rtp);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (TAKEOVER_COMMANDS.contains(name) && !plugin.getConfig().getBoolean("commands.takeover-enabled", true)) {
            messages.send(sender, "commands.takeover-disabled", "<yellow>Standard travel command takeover is disabled. Use <white>/ptravel</white> while staging.</yellow>");
            return true;
        }
        return switch (name) {
            case "spawn" -> player(sender, plugin::requestSpawn);
            case "hub" -> player(sender, plugin::requestHub);
            case "back" -> player(sender, plugin::requestBack);
            case "warp" -> player(sender, p -> warp(p, args));
            case "warps" -> player(sender, p -> { menus.openWarps(p, 0); return true; });
            case "travel" -> player(sender, p -> { menus.openHelp(p); return true; });
            case "rtp" -> player(sender, p -> rtpCommand(p, args));
            case "tpa" -> player(sender, p -> tpaCommand(p, args, TpaMode.TO_TARGET));
            case "tpahere" -> player(sender, p -> tpaCommand(p, args, TpaMode.TARGET_TO_REQUESTER));
            case "tpaccept" -> player(sender, p -> { tpa.accept(p, args.length == 0 ? null : args[0]); return true; });
            case "tpdeny" -> player(sender, p -> { tpa.deny(p, args.length == 0 ? null : args[0]); return true; });
            case "tpcancel" -> player(sender, p -> { tpa.cancel(p); return true; });
            case "setspawn" -> player(sender, p -> setDestination(p, "spawn", args));
            case "sethub" -> player(sender, p -> setDestination(p, "hub", args));
            case "setwarp" -> player(sender, p -> setWarp(p, args));
            case "delwarp" -> deleteWarp(sender, args);
            case "renamewarp" -> renameWarp(sender, args);
            case "ptravel" -> ptravel(sender, args);
            case "traveladmin" -> admin(sender, args);
            default -> false;
        };
    }

    private boolean player(CommandSender sender, PlayerAction action) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "commands.player-only", "<red>This command requires a player.</red>");
            return true;
        }
        return action.run(player);
    }

    private boolean warp(Player player, String[] args) {
        if (args.length == 0) {
            menus.openWarps(player, 0);
            return true;
        }
        return plugin.requestWarp(player, args[0]);
    }

    private boolean rtpCommand(Player player, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("now")) {
            rtp.begin(player);
            return true;
        }
        menus.openRtp(player);
        return true;
    }

    private boolean tpaCommand(Player requester, String[] args, TpaMode mode) {
        if (args.length < 1) {
            messages.sendRaw(requester, mode == TpaMode.TO_TARGET
                ? "<yellow>Usage: <white>/tpa &lt;player&gt;</white></yellow>"
                : "<yellow>Usage: <white>/tpahere &lt;player&gt;</white></yellow>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            messages.send(requester, "tpa.player-not-found", "<red>That player is not online.</red>");
            return true;
        }
        tpa.send(requester, target, mode);
        return true;
    }

    private boolean setDestination(Player player, String type, String[] args) {
        if (!player.hasPermission("plexontravel.admin.destinations")) return noPermission(player);
        boolean global = args.length > 0 && args[0].equalsIgnoreCase("global");
        if (type.equals("spawn")) {
            if (global) destinations.setGlobalSpawn(player.getLocation());
            else destinations.setWorldSpawn(player.getWorld(), player.getLocation());
        } else {
            if (global) destinations.setGlobalHub(player.getLocation());
            else destinations.setWorldHub(player.getWorld(), player.getLocation());
        }
        messages.sendRaw(player, "<green>" + capitalize(type) + " updated</green> <gray>for "
            + (global ? "the global fallback" : "<white>" + player.getWorld().getName() + "</white>") + ".</gray>");
        return true;
    }

    private boolean setWarp(Player player, String[] args) {
        if (!player.hasPermission("plexontravel.admin.warps")) return noPermission(player);
        if (args.length < 1) {
            messages.sendRaw(player, "<yellow>Usage: <white>/setwarp &lt;name&gt;</white></yellow>");
            return true;
        }
        String id = DestinationRegistry.normalizeId(args[0]);
        Warp previous = destinations.warp(id);
        Warp current = destinations.saveWarp(args[0], player.getLocation());
        if (current == null) {
            messages.sendRaw(player, "<red>Invalid warp name.</red>");
            return true;
        }
        if (previous == null) plugin.fire(new PlexonWarpCreatedEvent(current.view()));
        else plugin.fire(new PlexonWarpUpdatedEvent(previous.view(), current.view()));
        messages.sendRaw(player, "<green>Warp <white>" + current.id() + "</white> saved.</green>");
        return true;
    }

    private boolean deleteWarp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexontravel.admin.warps")) return noPermission(sender);
        if (args.length < 1) {
            messages.sendRaw(sender, "<yellow>Usage: <white>/delwarp &lt;name&gt;</white></yellow>");
            return true;
        }
        String id = DestinationRegistry.normalizeId(args[0]);
        Warp current = destinations.warp(id);
        if (current == null) {
            messages.sendRaw(sender, "<red>Warp not found.</red>");
            return true;
        }
        String actor = sender instanceof Player p ? "player:" + p.getUniqueId() : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
        DestructiveConfirmationGate.Decision decision = deleteGate.check(actor, "delete-warp", id, current.revision(), System.currentTimeMillis());
        if (decision == DestructiveConfirmationGate.Decision.ARMED) {
            messages.sendRaw(sender, "<yellow>Run <white>/delwarp " + id + "</white> again within 15s to confirm.</yellow>");
            return true;
        }
        if (!destinations.removeWarp(current)) {
            messages.sendRaw(sender, "<red>The warp changed before deletion. Confirmation was invalidated.</red>");
            return true;
        }
        deleteGate.invalidate("delete-warp", id);
        plugin.fire(new PlexonWarpDeletedEvent(current.view()));
        messages.sendRaw(sender, "<green>Warp <white>" + id + "</white> deleted.</green>");
        return true;
    }

    private boolean renameWarp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexontravel.admin.warps")) return noPermission(sender);
        if (args.length < 2) {
            messages.sendRaw(sender, "<yellow>Usage: <white>/renamewarp &lt;id&gt; &lt;display name...&gt;</white></yellow>");
            return true;
        }
        String id = DestinationRegistry.normalizeId(args[0]);
        Warp previous = destinations.warp(id);
        String displayName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        Warp renamed = destinations.renameWarp(id, displayName);
        if (previous == null || renamed == null) {
            messages.sendRaw(sender, "<red>Rename could not be completed.</red>");
            return true;
        }
        deleteGate.invalidate("delete-warp", id);
        plugin.fire(new PlexonWarpUpdatedEvent(previous.view(), renamed.view()));
        messages.sendRaw(sender, "<green>Warp renamed. Stable ID remains <white>" + id + "</white>.</green>");
        return true;
    }

    private boolean ptravel(CommandSender sender, String[] args) {
        if (args.length == 0) return player(sender, p -> { menus.openHelp(p); return true; });
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "spawn" -> player(sender, plugin::requestSpawn);
            case "hub" -> player(sender, plugin::requestHub);
            case "back" -> player(sender, plugin::requestBack);
            case "warp" -> player(sender, p -> warp(p, rest));
            case "warps" -> player(sender, p -> { menus.openWarps(p, 0); return true; });
            case "rtp" -> player(sender, p -> rtpCommand(p, rest));
            case "travel", "help" -> player(sender, p -> { menus.openHelp(p); return true; });
            default -> { messages.sendRaw(sender, "<red>Unknown travel subcommand.</red>"); yield true; }
        };
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexontravel.admin")) return noPermission(sender);
        if (args.length == 0) {
            messages.sendRaw(sender, "<yellow>Usage: <white>/traveladmin &lt;reload|diagnostics|backup|migrate|setspawn|sethub|rtp|void&gt;</white></yellow>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "reload" -> { reload(sender); yield true; }
            case "diagnostics" -> { diagnostics(sender); yield true; }
            case "backup" -> { backup(sender); yield true; }
            case "migrate" -> { migrate(sender, rest); yield true; }
            case "setspawn" -> player(sender, p -> setDestination(p, "spawn", rest));
            case "sethub" -> player(sender, p -> setDestination(p, "hub", rest));
            case "rtp" -> worldAdmin.rtp(sender, rest);
            case "void" -> worldAdmin.voidRescue(sender, rest);
            default -> { messages.sendRaw(sender, "<red>Unknown admin subcommand.</red>"); yield true; }
        };
    }

    private void reload(CommandSender sender) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(file);
        List<String> errors = new ArrayList<>(TravelConfigValidator.validate(candidate));
        errors.addAll(plugin.worldSettings().validateDisk());
        if (!errors.isEmpty()) {
            messages.sendRaw(sender, "<red>Reload rejected.</red> <gray>" + String.join("; ", errors) + "</gray>");
            return;
        }
        try {
            plugin.reloadConfig();
            plugin.worldSettings().reloadFromDisk();
            messages.reload();
            engine.invalidateWarmupsForReload();
            messages.sendRaw(sender, "<green>Configuration activated.</green> <gray>Main config and world-settings are valid; runtime epoch <white>" + engine.runtimeEpoch() + "</white>.</gray>");
        } catch (Exception failure) {
            plugin.getLogger().severe("Transactional reload activation failed after validation: " + failure.getMessage());
            messages.sendRaw(sender, "<red>Reload activation failed.</red> <gray>" + failure.getMessage() + "</gray>");
        }
    }

    private void diagnostics(CommandSender sender) {
        messages.sendRaw(sender, "<aqua><bold>PlexonTravel diagnostics</bold></aqua>");
        messages.sendRaw(sender, "<gray>Core:</gray> <white>" + plugin.core().version().pluginVersion() + "</white> <dark_gray>(API " + plugin.core().version().apiVersion() + ")</dark_gray>");
        messages.sendRaw(sender, "<gray>Destinations:</gray> <white>" + destinations.worldSpawnCount() + " world spawns, " + destinations.worldHubCount() + " world hubs, " + destinations.warpCount() + " warps</white>");
        messages.sendRaw(sender, "<gray>Travel:</gray> <white>pending=" + engine.pendingCount() + ", started=" + engine.startedCount() + ", completed=" + engine.completedCount() + "</white>");
        messages.sendRaw(sender, "<gray>Cancellation:</gray> <white>moved=" + engine.movedCancelledCount() + ", damage=" + engine.damageCancelledCount() + ", unsafe=" + engine.unsafeRejectedCount() + "</white>");
        messages.sendRaw(sender, "<gray>TPA:</gray> <white>active=" + tpa.activeRequests() + ", sent=" + tpa.sentCount() + ", accepted=" + tpa.acceptedCount() + ", denied=" + tpa.deniedCount() + ", expired=" + tpa.expiredCount() + "</white>");
        messages.sendRaw(sender, "<gray>RTP:</gray> <white>searching=" + rtp.activeSearches() + ", searches=" + rtp.searchCount() + ", found=" + rtp.foundCount() + ", exhausted=" + rtp.exhaustedCount() + ", explicit-profiles=" + plugin.worldSettings().explicitRtpCount() + ", legacy-inherited-loaded=" + plugin.worldSettings().legacyInheritedLoadedWorldCount() + "</white>");
        messages.sendRaw(sender, "<gray>Void rescue:</gray> <white>enabled-worlds=" + plugin.worldSettings().enabledVoidWorldCount() + ", active=" + plugin.voidRescue().activeCount() + ", attempted=" + plugin.voidRescue().attemptedCount() + ", completed=" + plugin.voidRescue().completedCount() + ", failed=" + plugin.voidRescue().failureCount() + "</white>");
        messages.sendRaw(sender, "<gray>World settings:</gray> <white>schema=" + WorldSettingsManager.SCHEMA_VERSION + ", explicit-void-rules=" + plugin.worldSettings().explicitVoidCount() + "</white>");
        messages.sendRaw(sender, "<gray>Command takeover:</gray> <white>" + plugin.getConfig().getBoolean("commands.takeover-enabled", true) + "</white> <dark_gray>| epoch=" + engine.runtimeEpoch() + "</dark_gray>");
    }

    private void backup(CommandSender sender) {
        try {
            Path target = plugin.getDataFolder().toPath().resolve("travel-backup-" + System.currentTimeMillis() + ".db");
            storage.backup(target);
            messages.sendRaw(sender, "<green>Database backup created:</green> <white>" + target.getFileName() + "</white>");
        } catch (Exception failure) {
            messages.sendRaw(sender, "<red>Backup failed:</red> <gray>" + failure.getMessage() + "</gray>");
        }
    }

    private void migrate(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "scan" : args[0].toLowerCase(Locale.ROOT);
        Path plugins = plugin.getDataFolder().toPath().getParent();
        Path essentials = plugins == null ? Path.of("plugins", "Essentials", "warps") : plugins.resolve("Essentials").resolve("warps");
        Path worldSpawn = plugins == null ? Path.of("plugins", "WorldSpawn") : plugins.resolve("WorldSpawn");
        long essentialsCount = countYaml(essentials);

        if (Set.of("scan", "plan", "status").contains(action)) {
            messages.sendRaw(sender, "<aqua>Migration scan</aqua> <gray>Essentials warps=<white>" + essentialsCount
                + "</white>, existing PlexonTravel warps=<white>" + destinations.warpCount()
                + "</white>, WorldSpawn data=<white>" + Files.isDirectory(worldSpawn) + "</white>.</gray>");
            messages.sendRaw(sender, "<dark_gray>Existing source files remain read-only. Per-world spawn/hub should be set with /setspawn and /sethub if WorldSpawn data cannot be mapped unambiguously.</dark_gray>");
            return;
        }
        if (!action.equals("execute")) {
            messages.sendRaw(sender, "<yellow>Usage: <white>/traveladmin migrate &lt;scan|plan|status|execute&gt;</white></yellow>");
            return;
        }
        if (!plugin.getConfig().getBoolean("migration.allow-execute", false)) {
            messages.sendRaw(sender, "<red>Migration execution is disabled.</red> <gray>Set migration.allow-execute=true only after scan and backup.</gray>");
            return;
        }
        importEssentialsWarps(sender, essentials);
    }

    private void importEssentialsWarps(CommandSender sender, Path folder) {
        if (!Files.isDirectory(folder)) {
            messages.sendRaw(sender, "<red>Essentials warp folder was not found.</red>");
            return;
        }
        int imported = 0;
        int skipped = 0;
        int unresolved = 0;
        try (var stream = Files.list(folder)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")).toList()) {
                String filename = file.getFileName().toString();
                String display = filename.substring(0, filename.length() - 4);
                String id = DestinationRegistry.normalizeId(display);
                if (id.isBlank() || destinations.warp(id) != null) { skipped++; continue; }
                YamlConfiguration source = YamlConfiguration.loadConfiguration(file.toFile());
                String worldName = source.getString("world", "");
                World world = Bukkit.getWorld(worldName);
                if (world == null) { unresolved++; continue; }
                Destination destination = new Destination(world.getUID(), world.getName(), source.getDouble("x"), source.getDouble("y"), source.getDouble("z"),
                    (float) source.getDouble("yaw"), (float) source.getDouble("pitch"));
                Warp warp = destinations.importWarp(display, destination, "Imported");
                if (warp == null) skipped++;
                else { plugin.fire(new PlexonWarpCreatedEvent(warp.view())); imported++; }
            }
        } catch (IOException failure) {
            messages.sendRaw(sender, "<red>Migration failed:</red> <gray>" + failure.getMessage() + "</gray>");
            return;
        }
        messages.sendRaw(sender, "<green>Migration complete.</green> <gray>imported=<white>" + imported + "</white>, skipped=<white>" + skipped + "</white>, unresolved=<white>" + unresolved + "</white>.</gray>");
    }

    private long countYaml(Path folder) {
        if (!Files.isDirectory(folder)) return 0L;
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")).count();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private boolean noPermission(CommandSender sender) {
        messages.send(sender, "commands.no-permission", "<red>You do not have permission.</red>");
        return true;
    }

    private String capitalize(String input) {
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("warp") && args.length == 1) return prefix(destinations.warpIds(), args[0]);
        if ((name.equals("delwarp") || name.equals("renamewarp")) && args.length == 1) return prefix(destinations.warpIds(), args[0]);
        if ((name.equals("setspawn") || name.equals("sethub")) && args.length == 1) return prefix(List.of("global"), args[0]);
        if ((name.equals("tpa") || name.equals("tpahere")) && args.length == 1) return onlinePlayers(sender, args[0]);
        if ((name.equals("tpaccept") || name.equals("tpdeny")) && args.length == 1 && sender instanceof Player player) return prefix(tpa.incomingNames(player), args[0]);
        if (name.equals("rtp") && args.length == 1) return prefix(List.of("now"), args[0]);
        if (name.equals("ptravel") && args.length == 1) return prefix(List.of("spawn", "hub", "back", "warp", "warps", "rtp", "travel"), args[0]);
        if (name.equals("ptravel") && args.length == 2 && args[0].equalsIgnoreCase("warp")) return prefix(destinations.warpIds(), args[1]);
        if (name.equals("traveladmin")) {
            if (args.length == 1) return prefix(List.of("reload", "diagnostics", "backup", "migrate", "setspawn", "sethub", "rtp", "void"), args[0]);
            if (args.length >= 2 && args[0].equalsIgnoreCase("rtp")) return worldAdmin.tabRtp(sender, Arrays.copyOfRange(args, 1, args.length));
            if (args.length >= 2 && args[0].equalsIgnoreCase("void")) return worldAdmin.tabVoid(sender, Arrays.copyOfRange(args, 1, args.length));
            if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) return prefix(List.of("scan", "plan", "status", "execute"), args[1]);
            if (args.length == 2 && (args[0].equalsIgnoreCase("setspawn") || args[0].equalsIgnoreCase("sethub"))) return prefix(List.of("global"), args[1]);
        }
        return List.of();
    }

    private List<String> onlinePlayers(CommandSender sender, String input) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player current && player.getUniqueId().equals(current.getUniqueId())) continue;
            names.add(player.getName());
        }
        return prefix(names, input);
    }

    private List<String> prefix(Iterable<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    @FunctionalInterface
    private interface PlayerAction { boolean run(Player player); }
}
