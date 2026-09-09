package com.plexon.travel.event;

import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PlexonTravelStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final TravelType type;
    private final TravelDestinationView destination;
    private final String sourceId;
    private boolean cancelled;

    public PlexonTravelStartEvent(Player player, TravelType type, TravelDestinationView destination, String sourceId) {
        this.player = player; this.type = type; this.destination = destination; this.sourceId = sourceId;
    }
    public Player getPlayer() { return player; }
    public TravelType getType() { return type; }
    public TravelDestinationView getDestination() { return destination; }
    public String getSourceId() { return sourceId; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
