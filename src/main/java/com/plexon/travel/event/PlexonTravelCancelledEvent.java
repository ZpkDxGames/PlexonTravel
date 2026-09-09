package com.plexon.travel.event;

import com.plexon.travel.api.PlexonTravelAPI.CancelReason;
import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PlexonTravelCancelledEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player; private final TravelType type; private final CancelReason reason; private final String sourceId;
    public PlexonTravelCancelledEvent(Player player, TravelType type, CancelReason reason, String sourceId) {
        this.player = player; this.type = type; this.reason = reason; this.sourceId = sourceId;
    }
    public Player getPlayer() { return player; }
    public TravelType getType() { return type; }
    public CancelReason getReason() { return reason; }
    public String getSourceId() { return sourceId; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
