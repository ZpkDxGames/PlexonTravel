package com.plexon.travel.event;

import com.plexon.travel.api.PlexonTravelAPI.WarpView;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PlexonWarpDeletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList(); private final WarpView warp;
    public PlexonWarpDeletedEvent(WarpView warp) { this.warp = warp; }
    public WarpView getWarp() { return warp; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
