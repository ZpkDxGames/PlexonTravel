package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
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
    private final WorldSettingsManager worldSettings;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private final LongAdder searches = new LongAdder();
    private final LongAdder found = new LongAdder();
    private final LongAdder exhausted = new LongAdder();

    RtpService(PlexonTravel plugin, TravelEngine engine, TravelMessages messages, WorldSettingsManager worldSettings) {
        this.plugin = plugin;
        this.engine = engine;
        this.messages = messages;
        this.worldSettings = worldSettings;
    }

    boolean begin(Player player) {
        if (!player.hasPermission("plexontravel.rtp")) {
            messages.send(player, "commands.no-permission", "<red>You do not have permission.</red>");
            return false;
        }
        World world = player.getWorld();
        RtpProfile profile = worldSettings.rtpProfile(world);
        if (!profile.enabled()) {
            messages.send(player, profile.source() == RtpProfileSource.LEGACY ? "rtp.world-not-allowed" : "rtp.disabled",
                "<red>Random teleport is disabled in this world.</red>");
            return false;
        }
        Optional<ResolvedBoundary> resolved = resolveBoundary(world, profile);
        if (resolved.isEmpty()) {
            messages.send(player, "rtp.invalid-profile", "<red>This world's RTP boundary has no valid area inside the world border.</red>");
            return false;
        }
        long remaining = engine.cooldownRemainingMillis(player, TravelType.RTP);
        if (remaining > 0L && !player.hasPermission("plexontravel.cooldown.bypass")) {
            messages.send(player, "teleport.cooldown", "<yellow>Wait <white>{seconds}s</white> before travelling again.</yellow>",
                Map.of("seconds", Math.max(1L, (remaining + 999L) / 1000L)));
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!searching.add(playerId)) {
            messages.send(player, "rtp.already-searching", "<yellow>A safe RTP destination is already being searched.</yellow>");
            return false;
        }

        searches.increment();
        messages.send(player, "rtp.searching", "<gray>Searching for a safe location inside this world's RTP boundary...</gray>");
        UUID worldId = world.getUID();
        search(world, playerId, profile, resolved.get()).whenComplete((destination, failure) -> runSync(() -> {
            if (!searching.remove(playerId)) return;
            if (!player.isOnline()) return;
            if (!player.getWorld().getUID().equals(worldId)) {
                messages.send(player, "rtp.cancelled-world-change", "<yellow>RTP search cancelled because you changed worlds.</yellow>");
                return;
            }
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

    void cancel(UUID playerId) { searching.remove(playerId); }
    void shutdown() { searching.clear(); }
    boolean isSearching(UUID playerId) { return searching.contains(playerId); }
    boolean isAllowed(World world) { return worldSettings.rtpProfile(world).enabled(); }
    RtpProfile profile(World world) { return worldSettings.rtpProfile(world); }

    String describe(World world) {
        RtpProfile profile = worldSettings.rtpProfile(world);
        return resolveBoundary(world, profile).map(ResolvedBoundary::describe).orElse("INVALID / NO EFFECTIVE AREA");
    }

    RtpTestResult test(World world, int requestedSamples) {
        int samples = Math.max(1, Math.min(256, requestedSamples));
        RtpProfile profile = worldSettings.rtpProfile(world);
        Optional<ResolvedBoundary> resolved = resolveBoundary(world, profile);
        if (resolved.isEmpty()) return new RtpTestResult(profile, 0, samples, samples, 0, 0, 0, 0, 0, true);
        ResolvedBoundary boundary = resolved.get();
        int valid = 0;
        int rejected = 0;
        int ungenerated = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < samples; i++) {
            RtpGeometry.Point point = boundary.sample(random);
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
            if (!boundary.contains(point.x() + 0.5D, point.z() + 0.5D)
                || !world.getWorldBorder().isInside(new Location(world, point.x() + 0.5D, world.getSpawnLocation().getY(), point.z() + 0.5D))) {
                rejected++;
                continue;
            }
            int chunkX = point.x() >> 4;
            int chunkZ = point.z() >> 4;
            if (!profile.generateChunks() && !world.isChunkGenerated(chunkX, chunkZ)) {
                ungenerated++;
                rejected++;
                continue;
            }
            valid++;
        }
        return new RtpTestResult(profile, valid, rejected, Math.max(0, samples - valid), ungenerated,
            minX == Integer.MAX_VALUE ? 0 : minX, maxX == Integer.MIN_VALUE ? 0 : maxX,
            minZ == Integer.MAX_VALUE ? 0 : minZ, maxZ == Integer.MIN_VALUE ? 0 : maxZ, false);
    }

    private CompletableFuture<Destination> search(World world, UUID playerId, RtpProfile profile, ResolvedBoundary boundary) {
        SearchState state = new SearchState(world, playerId, profile, boundary);
        state.nextAttempt();
        return state.future;
    }

    private Optional<ResolvedBoundary> resolveBoundary(World world, RtpProfile profile) {
        RtpGeometry.Bounds border = worldBorderBounds(world);
        if (border.empty()) return Optional.empty();
        return switch (profile.boundaryMode()) {
            case ANNULUS -> {
                Location center = profile.centerMode() == RtpCenterMode.CONFIGURED
                    ? new Location(world, profile.centerX(), world.getSpawnLocation().getY(), profile.centerZ())
                    : world.getSpawnLocation();
                if (!RtpGeometry.annulusIntersects(center.getX(), center.getZ(), profile.minRadius(), profile.maxRadius(), border)) yield Optional.empty();
                yield Optional.of(new AnnulusBoundary(center.getX(), center.getZ(), profile.minRadius(), profile.maxRadius(), border));
            }
            case RECTANGLE -> {
                RtpGeometry.Bounds configured = new RtpGeometry.Bounds(profile.minX(), profile.maxX(), profile.minZ(), profile.maxZ());
                RtpGeometry.Bounds effective = RtpGeometry.intersect(configured, border);
                yield effective.empty() ? Optional.empty() : Optional.of(new RectangleBoundary(effective));
            }
            case WORLD_BORDER -> {
                RtpGeometry.Bounds effective = RtpGeometry.padded(border, profile.worldBorderPadding());
                yield effective.empty() ? Optional.empty() : Optional.of(new WorldBorderBoundary(effective, profile.worldBorderPadding()));
            }
        };
    }

    private RtpGeometry.Bounds worldBorderBounds(World world) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2D;
        RtpGeometry.Bounds raw = new RtpGeometry.Bounds(center.getX() - half, center.getX() + half, center.getZ() - half, center.getZ() + half);
        RtpGeometry.Bounds minecraft = new RtpGeometry.Bounds(-29_999_984D, 29_999_984D, -29_999_984D, 29_999_984D);
        return RtpGeometry.intersect(raw, minecraft);
    }

    private void runSync(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin, action);
    }

    long searchCount() { return searches.sum(); }
    long foundCount() { return found.sum(); }
    long exhaustedCount() { return exhausted.sum(); }
    int activeSearches() { return searching.size(); }

    record RtpTestResult(RtpProfile profile, int validSamples, int rejectedSamples, int exhaustedSamples, int ungeneratedRejected,
                         int minX, int maxX, int minZ, int maxZ, boolean invalidBoundary) {}

    private interface ResolvedBoundary {
        RtpGeometry.Point sample(ThreadLocalRandom random);
        boolean contains(double x, double z);
        String describe();
    }

    private record AnnulusBoundary(double centerX, double centerZ, double minRadius, double maxRadius, RtpGeometry.Bounds border) implements ResolvedBoundary {
        @Override public RtpGeometry.Point sample(ThreadLocalRandom random) { return RtpGeometry.sampleAnnulus(centerX, centerZ, minRadius, maxRadius, random); }
        @Override public boolean contains(double x, double z) { double distance = Math.hypot(x - centerX, z - centerZ); return distance >= minRadius && distance <= maxRadius && border.contains(x, z); }
        @Override public String describe() { return "ANNULUS center=" + Math.round(centerX) + "," + Math.round(centerZ) + " radius=" + Math.round(minRadius) + ".." + Math.round(maxRadius); }
    }

    private record RectangleBoundary(RtpGeometry.Bounds bounds) implements ResolvedBoundary {
        @Override public RtpGeometry.Point sample(ThreadLocalRandom random) { return RtpGeometry.sampleRectangle(bounds, random); }
        @Override public boolean contains(double x, double z) { return bounds.contains(x, z); }
        @Override public String describe() { return "RECTANGLE x=" + Math.round(bounds.minX()) + ".." + Math.round(bounds.maxX()) + " z=" + Math.round(bounds.minZ()) + ".." + Math.round(bounds.maxZ()); }
    }

    private record WorldBorderBoundary(RtpGeometry.Bounds bounds, double padding) implements ResolvedBoundary {
        @Override public RtpGeometry.Point sample(ThreadLocalRandom random) { return RtpGeometry.sampleRectangle(bounds, random); }
        @Override public boolean contains(double x, double z) { return bounds.contains(x, z); }
        @Override public String describe() { return "WORLD_BORDER padding=" + Math.round(padding); }
    }

    private final class SearchState {
        private final World world;
        private final UUID playerId;
        private final RtpProfile profile;
        private final ResolvedBoundary boundary;
        private final CompletableFuture<Destination> future = new CompletableFuture<>();
        private int attempt;

        private SearchState(World world, UUID playerId, RtpProfile profile, ResolvedBoundary boundary) {
            this.world = world;
            this.playerId = playerId;
            this.profile = profile;
            this.boundary = boundary;
        }

        private void nextAttempt() {
            if (future.isDone()) return;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (attempt < profile.maxAttempts()) {
                if (!searching.contains(playerId)) { future.complete(null); return; }
                attempt++;
                RtpGeometry.Point point = boundary.sample(random);
                if (!boundary.contains(point.x() + 0.5D, point.z() + 0.5D)) continue;
                Location borderProbe = new Location(world, point.x() + 0.5D, world.getSpawnLocation().getY(), point.z() + 0.5D);
                if (!world.getWorldBorder().isInside(borderProbe)) continue;
                int chunkX = point.x() >> 4;
                int chunkZ = point.z() >> 4;
                if (!profile.generateChunks() && !world.isChunkGenerated(chunkX, chunkZ)) continue;
                world.getChunkAtAsync(chunkX, chunkZ, profile.generateChunks()).whenComplete((chunk, failure) -> runSync(() -> {
                    if (future.isDone()) return;
                    if (!searching.contains(playerId)) { future.complete(null); return; }
                    if (failure != null || chunk == null) { nextAttempt(); return; }
                    int y = world.getHighestBlockYAt(point.x(), point.z(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                    Location candidate = new Location(world, point.x() + 0.5D, y, point.z() + 0.5D, ThreadLocalRandom.current().nextFloat() * 360F - 180F, 0F);
                    if (engine.isSafe(candidate)) future.complete(Destination.from(candidate)); else nextAttempt();
                }));
                return;
            }
            future.complete(null);
        }
    }
}
