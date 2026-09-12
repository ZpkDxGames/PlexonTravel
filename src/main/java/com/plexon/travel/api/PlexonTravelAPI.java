package com.plexon.travel.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Public immutable service contract exposed through Bukkit ServicesManager. */
public interface PlexonTravelAPI {
    Optional<TravelDestinationView> spawn();
    Optional<TravelDestinationView> hub();

    /** World-aware destination lookup added in PlexonTravel 3.0. */
    default Optional<TravelDestinationView> spawn(UUID worldId) { return spawn(); }

    /** World-aware destination lookup added in PlexonTravel 3.0. */
    default Optional<TravelDestinationView> hub(UUID worldId) { return hub(); }

    Optional<WarpView> warp(String id);
    List<WarpView> warps();

    /** Cached O(1) implementations may override this to avoid materializing the warp list. */
    default int warpCount() { return warps().size(); }

    Optional<BackLocationView> back(UUID playerId);
    TravelStatusView status(UUID playerId);
    boolean isPending(UUID playerId);
    boolean cancelPending(UUID playerId);
    CompletableFuture<Boolean> teleport(UUID playerId, TravelDestinationView destination, TravelType type);

    enum TravelType { SPAWN, HUB, WARP, BACK, TPA, RTP, ADMIN }

    enum CancelReason {
        MOVED, DAMAGED, DIED, QUIT, WORLD_CHANGED, DESTINATION_UNSAFE, DESTINATION_MISSING,
        PERMISSION, COOLDOWN, ECONOMY, COMBAT, REPLACED, PLUGIN_DISABLED, EXPIRED, DENIED, OTHER
    }

    record TravelDestinationView(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {}

    record WarpView(String id, String displayName, TravelDestinationView destination, boolean enabled,
                    String permission, int sortOrder, String icon, String category, long revision) {}

    record BackLocationView(TravelDestinationView destination, String source, long updatedAt) {}

    record TravelStatusView(boolean pending, TravelType type, String sourceId, long remainingMillis) {
        public static TravelStatusView idle() { return new TravelStatusView(false, null, "", 0L); }
    }
}
