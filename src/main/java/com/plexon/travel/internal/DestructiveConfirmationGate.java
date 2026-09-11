package com.plexon.travel.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Actor/action/resource/revision-bound, expiring and one-shot destructive confirmation gate. */
public final class DestructiveConfirmationGate {
    public enum Decision { ARMED, CONFIRMED }

    private final long ttlMillis;
    private final Map<Key, Entry> armed = new HashMap<>();

    public DestructiveConfirmationGate(long ttlMillis) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis must be positive");
        this.ttlMillis = ttlMillis;
    }

    public synchronized Decision check(String actor, String action, String resourceId, long revision, long nowMillis) {
        Key key = new Key(require(actor), require(action), require(resourceId));
        Entry previous = armed.remove(key);
        purge(nowMillis);
        if (previous != null && previous.expiresAtMillis >= nowMillis && previous.revision == revision) {
            return Decision.CONFIRMED;
        }
        armed.put(key, new Entry(revision, nowMillis + ttlMillis));
        return Decision.ARMED;
    }

    public synchronized void invalidate(String action, String resourceId) {
        armed.keySet().removeIf(key -> key.action.equals(action) && key.resourceId.equals(resourceId));
    }

    private void purge(long nowMillis) { armed.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < nowMillis); }
    private static String require(String value) { return Objects.requireNonNull(value, "value").trim(); }
    private record Key(String actor, String action, String resourceId) {}
    private record Entry(long revision, long expiresAtMillis) {}
}
