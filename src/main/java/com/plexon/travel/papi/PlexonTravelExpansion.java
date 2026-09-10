package com.plexon.travel.papi;

import com.plexon.travel.api.PlexonTravelAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** PlaceholderAPI bridge backed exclusively by already-cached PlexonTravel API state. */
public final class PlexonTravelExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final PlexonTravelAPI api;

    public PlexonTravelExpansion(Plugin plugin, PlexonTravelAPI api) { this.plugin = plugin; this.api = api; }
    @Override public @NotNull String getIdentifier() { return "plexontravel"; }
    @Override public @NotNull String getAuthor() { return String.join(",", plugin.getPluginMeta().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "spawn_configured" -> Boolean.toString(api.spawn().isPresent());
            case "hub_configured" -> Boolean.toString(api.hub().isPresent());
            case "warps" -> Integer.toString(api.warps().size());
            case "pending" -> player == null ? "false" : Boolean.toString(api.isPending(player.getUniqueId()));
            case "back_available" -> player == null ? "false" : Boolean.toString(api.back(player.getUniqueId()).isPresent());
            default -> null;
        };
    }
}
