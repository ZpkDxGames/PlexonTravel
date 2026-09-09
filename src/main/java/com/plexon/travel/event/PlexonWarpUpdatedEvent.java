package com.plexon.travel.event;

import com.plexon.travel.api.PlexonTravelAPI.WarpView;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PlexonWarpUpdatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList(); private final WarpView previous; private final WarpView current;
    public PlexonWarpUpdatedEvent(WarpView previous, WarpView current) { this.previous = previous; this.current = current; }
    public WarpView getPrevious() { return previous; }
    public WarpView getCurrent() { return current; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
