package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.BackLocationView;
import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import com.plexon.travel.api.PlexonTravelAPI.WarpView;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

record Destination(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
    static Destination from(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return new Destination(location.getWorld().getUID(), location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    static Destination from(TravelDestinationView view) {
        if (view == null) return null;
        return new Destination(view.worldId(), view.worldName(), view.x(), view.y(), view.z(), view.yaw(), view.pitch());
    }

    static boolean finite(Location location) {
        return location != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ());
    }

    boolean finite() {
        return worldId != null && worldName != null && !worldName.isBlank()
            && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    Location toLocation() {
        World world = Bukkit.getWorld(worldId);
        if (world == null) world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    TravelDestinationView view() {
        return new TravelDestinationView(worldId, worldName, x, y, z, yaw, pitch);
    }
}

record Warp(String id, String displayName, Destination destination, boolean enabled, String permission,
            boolean permissionRequired, int sortOrder, String icon, String category, long revision) {
    WarpView view() {
        return new WarpView(id, displayName, destination.view(), enabled, permission, sortOrder, icon, category, revision);
    }
}

record BackEntry(Destination destination, String source, long updatedAt) {
    BackLocationView view() {
        return new BackLocationView(destination.view(), source, updatedAt);
    }
}

record Policy(int warmupSeconds, int cooldownSeconds, double fee) {}

enum TpaMode {
    TO_TARGET,
    TARGET_TO_REQUESTER
}

record TpaRequest(UUID requesterId, UUID targetId, TpaMode mode, long createdAtMillis, long expiresAtMillis) {
    boolean expired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
