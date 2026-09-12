package com.plexon.travel.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonVoidRescueEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location source;
    private final double triggerY;
    private final String destinationMode;
    private final Location destination;
    private boolean cancelled;

    public PlexonVoidRescueEvent(Player player, Location source, double triggerY, String destinationMode, Location destination) {
        this.player = player;
        this.source = source == null ? null : source.clone();
        this.triggerY = triggerY;
        this.destinationMode = destinationMode;
        this.destination = destination == null ? null : destination.clone();
    }

    public Player getPlayer() { return player; }
    public Location getSource() { return source == null ? null : source.clone(); }
    public double getTriggerY() { return triggerY; }
    public String getDestinationMode() { return destinationMode; }
    public Location getDestination() { return destination == null ? null : destination.clone(); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
