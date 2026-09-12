package com.plexon.travel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class TravelWorldAdmin {
    private final PlexonTravel plugin;
    private final TravelMessages messages;
    private final RtpService rtp;
    private final Map<UUID, RectangleSelection> rectangleSelections = new HashMap<>();

    TravelWorldAdmin(PlexonTravel plugin, TravelMessages messages, RtpService rtp) {
        this.plugin = plugin;
        this.messages = messages;
        this.rtp = rtp;
    }

    boolean rtp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexontravel.admin.rtp")) return noPermission(sender);
        if (args.length == 0) {
            World world = sender instanceof Player player ? player.getWorld() : null;
            if (world != null) statusRtp(sender, world);
            else rtpUsage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "status" -> { World world = world(sender, args, 1, true); if (world != null) statusRtp(sender, world); yield true; }
                case "enable", "disable" -> { World world = world(sender, args, 1, false); if (world != null) mutateRtp(sender, world, p -> p.withEnabled(action.equals("enable"))); yield true; }
                case "mode" -> { rtpMode(sender, args); yield true; }
                case "center" -> { rtpCenter(sender, args); yield true; }
                case "radius" -> { rtpRadius(sender, args); yield true; }
                case "bounds" -> { rtpBounds(sender, args); yield true; }
                case "pos1", "pos2" -> { rtpCorner(sender, args, action.equals("pos1")); yield true; }
                case "padding" -> { rtpPadding(sender, args); yield true; }
                case "attempts" -> { rtpAttempts(sender, args); yield true; }
                case "generate" -> { rtpGenerate(sender, args); yield true; }
                case "reset" -> { rtpReset(sender, args); yield true; }
                case "test" -> { rtpTest(sender, args); yield true; }
                default -> { rtpUsage(sender); yield true; }
            };
        } catch (IllegalArgumentException | IOException failure) {
            error(sender, failure.getMessage());
            return true;
        }
    }

    boolean voidRescue(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexontravel.admin.void")) return noPermission(sender);
        if (args.length == 0) {
            World world = sender instanceof Player player ? player.getWorld() : null;
            if (world != null) statusVoid(sender, world);
            else voidUsage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "status" -> { World world = world(sender, args, 1, true); if (world != null) statusVoid(sender, world); yield true; }
                case "enable", "disable" -> { World world = world(sender, args, 1, false); if (world != null) mutateVoid(sender, world, r -> r.withEnabled(action.equals("enable"))); yield true; }
                case "threshold" -> { voidThreshold(sender, args); yield true; }
                case "destination" -> { voidDestination(sender, args); yield true; }
                case "creative" -> { voidCreative(sender, args); yield true; }
                case "reset" -> { voidReset(sender, args); yield true; }
                case "test" -> { World world = world(sender, args, 1, true); if (world != null) testVoid(sender, world); yield true; }
                case "simulate" -> { voidSimulate(sender, args); yield true; }
                default -> { voidUsage(sender); yield true; }
            };
        } catch (IllegalArgumentException | IOException failure) {
            error(sender, failure.getMessage());
            return true;
        }
    }

    private void statusRtp(CommandSender sender, World world) {
        RtpProfile profile = plugin.worldSettings().rtpProfile(world);
        messages.sendRaw(sender, "<aqua><bold>RTP — " + world.getName() + "</bold></aqua>");
        messages.sendRaw(sender, "<gray>Enabled:</gray> <white>" + yesNo(profile.enabled()) + "</white>");
        messages.sendRaw(sender, "<gray>Source:</gray> <white>" + (profile.source() == RtpProfileSource.EXPLICIT ? "explicit 3.1 profile" : "legacy 3.0.x inheritance") + "</white>");
        messages.sendRaw(sender, "<gray>Boundary:</gray> <white>" + rtp.describe(world) + "</white>");
        messages.sendRaw(sender, "<gray>Attempts:</gray> <white>" + profile.maxAttempts() + "</white> <dark_gray>|</dark_gray> <gray>Generate chunks:</gray> <white>" + yesNo(profile.generateChunks()) + "</white>");
        messages.sendRaw(sender, "<gray>World border:</gray> <white>enforced</white>");
    }

    private void statusVoid(CommandSender sender, World world) {
        VoidRescueRule rule = plugin.worldSettings().voidRule(world);
        messages.sendRaw(sender, "<aqua><bold>Void Rescue — " + world.getName() + "</bold></aqua>");
        messages.sendRaw(sender, "<gray>Enabled:</gray> <white>" + yesNo(rule.enabled()) + "</white> <dark_gray>|</dark_gray> <gray>Source:</gray> <white>" + (plugin.worldSettings().hasExplicitVoid(world.getUID()) ? "explicit 3.1 rule" : "default disabled") + "</white>");
        messages.sendRaw(sender, "<gray>Trigger Y:</gray> <white>" + trim(rule.triggerY()) + "</white>");
        messages.sendRaw(sender, "<gray>Destination:</gray> <white>" + rule.destination().mode().name() + "</white> <dark_gray>|</dark_gray> <gray>Creative:</gray> <white>" + yesNo(rule.rescueCreative()) + "</white>");
        messages.sendRaw(sender, "<gray>Bypass:</gray> <white>" + VoidRescueService.BYPASS_PERMISSION + "</white>");
        messages.sendRaw(sender, "<gray>Rescues:</gray> <white>" + plugin.voidRescue().completedCount() + " success / " + plugin.voidRescue().failureCount() + " failed</white>");
    }

    private void rtpMode(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin rtp mode <world> <annulus|rectangle|world-border>");
        World world = requireWorld(args[1]);
        RtpBoundaryMode mode = parseEnum(args[2], RtpBoundaryMode.class, "boundary mode");
        mutateRtp(sender, world, p -> p.withMode(mode));
    }

    private void rtpCenter(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin rtp center <world> <spawn|here|x z>");
        World world = requireWorld(args[1]);
        if (args[2].equalsIgnoreCase("spawn")) {
            mutateRtp(sender, world, p -> p.withCenter(RtpCenterMode.WORLD_SPAWN, p.centerX(), p.centerZ()));
            return;
        }
        if (args[2].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("The 'here' center requires a player sender.");
            Location location = player.getLocation();
            mutateRtp(sender, world, p -> p.withCenter(RtpCenterMode.CONFIGURED, location.getX(), location.getZ()));
            return;
        }
        require(args, 4, "/traveladmin rtp center <world> <x> <z>");
        double x = number(args[2], "center x");
        double z = number(args[3], "center z");
        mutateRtp(sender, world, p -> p.withCenter(RtpCenterMode.CONFIGURED, x, z));
    }

    private void rtpRadius(CommandSender sender, String[] args) throws IOException {
        require(args, 4, "/traveladmin rtp radius <world> <min> <max>");
        World world = requireWorld(args[1]);
        double min = number(args[2], "minimum radius");
        double max = number(args[3], "maximum radius");
        mutateRtp(sender, world, p -> p.withRadius(min, max));
    }

    private void rtpBounds(CommandSender sender, String[] args) throws IOException {
        require(args, 6, "/traveladmin rtp bounds <world> <minX> <maxX> <minZ> <maxZ>");
        World world = requireWorld(args[1]);
        double minX = number(args[2], "minX");
        double maxX = number(args[3], "maxX");
        double minZ = number(args[4], "minZ");
        double maxZ = number(args[5], "maxZ");
        mutateRtp(sender, world, p -> p.withBounds(minX, maxX, minZ, maxZ));
    }

    private void rtpCorner(CommandSender sender, String[] args, boolean first) throws IOException {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("RTP corner capture requires a player sender.");
        World world = args.length >= 2 ? requireWorld(args[1]) : player.getWorld();
        Location location = player.getLocation();
        RectangleSelection previous = rectangleSelections.getOrDefault(player.getUniqueId(), new RectangleSelection(null, null, world.getUID()));
        if (!previous.worldId().equals(world.getUID())) previous = new RectangleSelection(null, null, world.getUID());
        Corner captured = new Corner(location.getX(), location.getZ());
        RectangleSelection current = first ? new RectangleSelection(captured, previous.pos2(), world.getUID()) : new RectangleSelection(previous.pos1(), captured, world.getUID());
        rectangleSelections.put(player.getUniqueId(), current);
        messages.sendRaw(sender, "<green>RTP " + (first ? "pos1" : "pos2") + " captured.</green> <gray>x=<white>" + trim(captured.x()) + "</white>, z=<white>" + trim(captured.z()) + "</white>.</gray>");
        if (current.pos1() != null && current.pos2() != null) {
            double minX = Math.min(current.pos1().x(), current.pos2().x());
            double maxX = Math.max(current.pos1().x(), current.pos2().x());
            double minZ = Math.min(current.pos1().z(), current.pos2().z());
            double maxZ = Math.max(current.pos1().z(), current.pos2().z());
            mutateRtp(sender, world, p -> p.withBounds(minX, maxX, minZ, maxZ).withMode(RtpBoundaryMode.RECTANGLE));
            rectangleSelections.remove(player.getUniqueId());
            messages.sendRaw(sender, "<gray>Corner order was normalized and RECTANGLE mode activated.</gray>");
        }
    }

    private void rtpPadding(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin rtp padding <world> <blocks>");
        World world = requireWorld(args[1]);
        double padding = number(args[2], "padding");
        mutateRtp(sender, world, p -> p.withPadding(padding));
    }

    private void rtpAttempts(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin rtp attempts <world> <count>");
        World world = requireWorld(args[1]);
        int count = integer(args[2], "attempt count");
        mutateRtp(sender, world, p -> p.withAttempts(count));
    }

    private void rtpGenerate(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin rtp generate <world> <true|false>");
        World world = requireWorld(args[1]);
        boolean enabled = bool(args[2]);
        mutateRtp(sender, world, p -> p.withGenerateChunks(enabled));
    }

    private void rtpReset(CommandSender sender, String[] args) throws IOException {
        require(args, 2, "/traveladmin rtp reset <world>");
        World world = requireWorld(args[1]);
        plugin.worldSettings().resetRtp(world);
        messages.sendRaw(sender, "<green>RTP profile reset.</green> <gray>" + world.getName() + " now inherits the legacy/default RTP policy.</gray>");
    }

    private void rtpTest(CommandSender sender, String[] args) {
        World world = world(sender, args, 1, true);
        if (world == null) return;
        int samples = args.length >= 3 ? integer(args[2], "sample count") : 32;
        RtpService.RtpTestResult result = rtp.test(world, samples);
        messages.sendRaw(sender, "<aqua><bold>RTP test — " + world.getName() + "</bold></aqua> <dark_gray>(non-teleporting)</dark_gray>");
        messages.sendRaw(sender, "<gray>Mode:</gray> <white>" + result.profile().boundaryMode() + "</white> <dark_gray>|</dark_gray> <gray>valid=<white>" + result.validSamples() + "</white>, rejected=<white>" + result.rejectedSamples() + "</white>, exhausted=<white>" + result.exhaustedSamples() + "</white></gray>");
        messages.sendRaw(sender, "<gray>Sample X:</gray> <white>" + result.minX() + ".." + result.maxX() + "</white> <dark_gray>|</dark_gray> <gray>Z:</gray> <white>" + result.minZ() + ".." + result.maxZ() + "</white> <dark_gray>|</dark_gray> <gray>ungenerated rejected=<white>" + result.ungeneratedRejected() + "</white></gray>");
        if (result.invalidBoundary()) messages.sendRaw(sender, "<red>The effective RTP boundary is empty or invalid against the current world border.</red>");
    }

    private void voidThreshold(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin void threshold <world> <y>");
        World world = requireWorld(args[1]);
        double y = number(args[2], "trigger Y");
        mutateVoid(sender, world, r -> r.withTriggerY(y));
    }

    private void voidDestination(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin void destination <world> <spawn|hub|worldspawn|here>");
        World world = requireWorld(args[1]);
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (mode.equals("here")) {
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("The 'here' destination requires a player sender.");
            Destination captured = Destination.from(player.getLocation());
            mutateVoid(sender, world, r -> r.withDestination(VoidDestination.fixed(captured)));
            return;
        }
        VoidDestinationMode destinationMode = switch (mode) {
            case "spawn", "plexon-spawn", "plexon_spawn" -> VoidDestinationMode.PLEXON_SPAWN;
            case "hub", "plexon-hub", "plexon_hub" -> VoidDestinationMode.PLEXON_HUB;
            case "worldspawn", "world-spawn", "world_spawn" -> VoidDestinationMode.WORLD_SPAWN;
            default -> throw new IllegalArgumentException("Unknown void destination mode: " + args[2]);
        };
        mutateVoid(sender, world, r -> r.withDestination(VoidDestination.ofMode(destinationMode)));
    }

    private void voidCreative(CommandSender sender, String[] args) throws IOException {
        require(args, 3, "/traveladmin void creative <world> <true|false>");
        World world = requireWorld(args[1]);
        boolean enabled = bool(args[2]);
        mutateVoid(sender, world, r -> r.withRescueCreative(enabled));
    }

    private void voidReset(CommandSender sender, String[] args) throws IOException {
        require(args, 2, "/traveladmin void reset <world>");
        World world = requireWorld(args[1]);
        plugin.worldSettings().resetVoid(world);
        messages.sendRaw(sender, "<green>Void rescue rule reset.</green> <gray>" + world.getName() + " now uses the default disabled rule.</gray>");
    }

    private void testVoid(CommandSender sender, World world) {
        VoidRescueRule rule = plugin.worldSettings().voidRule(world);
        Destination destination = plugin.voidRescue().resolvedDestination(world);
        Location location = destination == null ? null : destination.toLocation();
        messages.sendRaw(sender, "<aqua><bold>Void rescue test — " + world.getName() + "</bold></aqua> <dark_gray>(non-teleporting)</dark_gray>");
        messages.sendRaw(sender, "<gray>Enabled:</gray> <white>" + yesNo(rule.enabled()) + "</white> <dark_gray>|</dark_gray> <gray>Trigger Y:</gray> <white>" + trim(rule.triggerY()) + "</white> <dark_gray>|</dark_gray> <gray>Mode:</gray> <white>" + rule.destination().mode() + "</white>");
        if (location == null) messages.sendRaw(sender, "<red>Destination does not currently resolve to a loaded, configured target.</red>");
        else messages.sendRaw(sender, "<gray>Resolved:</gray> <white>" + location.getWorld().getName() + " " + trim(location.getX()) + ", " + trim(location.getY()) + ", " + trim(location.getZ()) + "</white>");
    }

    private void voidSimulate(CommandSender sender, String[] args) {
        require(args, 3, "/traveladmin void simulate <world> <y>");
        World world = requireWorld(args[1]);
        double y = number(args[2], "simulated Y");
        VoidRescueRule rule = plugin.worldSettings().voidRule(world);
        boolean trigger = rule.enabled() && y <= rule.triggerY();
        messages.sendRaw(sender, "<aqua>Void simulation:</aqua> <gray>world=<white>" + world.getName() + "</white>, y=<white>" + trim(y) + "</white>, threshold=<white>" + trim(rule.triggerY()) + "</white>, result=<white>" + (trigger ? "TRIGGER" : "NO_TRIGGER") + "</white>.</gray>");
    }

    private void mutateRtp(CommandSender sender, World world, java.util.function.UnaryOperator<RtpProfile> mutation) throws IOException {
        RtpProfile profile = plugin.worldSettings().mutateRtp(world, mutation);
        messages.sendRaw(sender, "<green>RTP profile updated.</green> <gray>" + world.getName() + " — <white>" + profile.boundaryMode() + "</white>, enabled=<white>" + yesNo(profile.enabled()) + "</white>.</gray>");
    }

    private void mutateVoid(CommandSender sender, World world, java.util.function.UnaryOperator<VoidRescueRule> mutation) throws IOException {
        VoidRescueRule rule = plugin.worldSettings().mutateVoid(world, mutation);
        messages.sendRaw(sender, "<green>Void rescue rule updated.</green> <gray>" + world.getName() + " — enabled=<white>" + yesNo(rule.enabled()) + "</white>, trigger=<white>" + trim(rule.triggerY()) + "</white>.</gray>");
    }

    List<String> tabRtp(CommandSender sender, String[] args) {
        if (args.length <= 1) return prefix(List.of("status", "enable", "disable", "mode", "center", "radius", "bounds", "pos1", "pos2", "padding", "attempts", "generate", "reset", "test"), args.length == 0 ? "" : args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !action.equals("test")) return prefix(worldNames(), args[1]);
        if (args.length == 2 && action.equals("test")) return prefix(worldNames(), args[1]);
        if (args.length == 3 && action.equals("mode")) return prefix(List.of("annulus", "rectangle", "world-border"), args[2]);
        if (args.length == 3 && action.equals("center")) return prefix(List.of("spawn", "here"), args[2]);
        if (args.length == 3 && action.equals("generate")) return prefix(List.of("true", "false"), args[2]);
        return List.of();
    }

    List<String> tabVoid(CommandSender sender, String[] args) {
        if (args.length <= 1) return prefix(List.of("status", "enable", "disable", "threshold", "destination", "creative", "reset", "test", "simulate"), args.length == 0 ? "" : args[0]);
        if (args.length == 2) return prefix(worldNames(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("destination")) return prefix(List.of("spawn", "hub", "worldspawn", "here"), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("creative")) return prefix(List.of("true", "false"), args[2]);
        return List.of();
    }

    private World world(CommandSender sender, String[] args, int index, boolean allowCurrent) {
        if (args.length > index) return requireWorld(args[index]);
        if (allowCurrent && sender instanceof Player player) return player.getWorld();
        throw new IllegalArgumentException("A world name is required from console.");
    }

    private World requireWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            try { world = Bukkit.getWorld(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
        }
        if (world == null) throw new IllegalArgumentException("World is not loaded: " + name);
        return world;
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Usage: " + usage);
    }

    private static double number(String raw, String label) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " must be a finite number.");
        }
    }

    private static int integer(String raw, String label) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(label + " must be an integer."); }
    }

    private static boolean bool(String raw) {
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Expected true or false.");
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String label) {
        try { return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown " + label + ": " + raw); }
    }

    private List<String> worldNames() {
        List<String> values = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) values.add(world.getName());
        return values;
    }

    private static List<String> prefix(Iterable<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    private boolean noPermission(CommandSender sender) {
        messages.send(sender, "commands.no-permission", "<red>You do not have permission.</red>");
        return true;
    }

    private void error(CommandSender sender, String detail) {
        messages.sendRaw(sender, "<red>Update rejected.</red> <gray>" + (detail == null ? "Invalid settings." : detail) + "</gray>");
    }

    private void rtpUsage(CommandSender sender) {
        messages.sendRaw(sender, "<yellow>Usage: <white>/traveladmin rtp &lt;status|enable|disable|mode|center|radius|bounds|pos1|pos2|padding|attempts|generate|reset|test&gt; ...</white></yellow>");
    }

    private void voidUsage(CommandSender sender) {
        messages.sendRaw(sender, "<yellow>Usage: <white>/traveladmin void &lt;status|enable|disable|threshold|destination|creative|reset|test|simulate&gt; ...</white></yellow>");
    }

    private static String yesNo(boolean value) { return value ? "yes" : "no"; }
    private static String trim(double value) { return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.00$", ""); }

    private record Corner(double x, double z) {}
    private record RectangleSelection(Corner pos1, Corner pos2, UUID worldId) {}
}
