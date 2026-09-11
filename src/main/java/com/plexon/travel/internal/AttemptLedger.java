package com.plexon.travel.internal;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** O(1) ownership ledger spanning destination resolution through async teleport terminal state. */
public final class AttemptLedger {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<UUID, Long> active = new ConcurrentHashMap<>();

    /** Returns a positive identity, or 0 when the player already owns an active attempt. */
    public long acquire(UUID playerId) {
        long token = sequence.incrementAndGet();
        return active.putIfAbsent(playerId, token) == null ? token : 0L;
    }

    public boolean owns(UUID playerId, long token) {
        Long current = active.get(playerId);
        return current != null && current == token;
    }

    public boolean release(UUID playerId, long token) {
        return active.remove(playerId, token);
    }

    public boolean releaseCurrent(UUID playerId) {
        return active.remove(playerId) != null;
    }

    public boolean isActive(UUID playerId) { return active.containsKey(playerId); }
    public int size() { return active.size(); }
    public void clear() { active.clear(); }
}
