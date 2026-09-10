package com.plexon.travel;

import com.plexon.travel.internal.AttemptLedger;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AttemptLedgerTest {
    @Test void rejectsDuplicateOwnershipUntilTerminalRelease() {
        AttemptLedger ledger = new AttemptLedger(); UUID id = UUID.randomUUID();
        long first = ledger.acquire(id); assertTrue(first > 0); assertEquals(0L, ledger.acquire(id));
        assertTrue(ledger.release(id, first)); assertTrue(ledger.acquire(id) > 0);
    }
    @Test void staleTokenCannotReleaseNewerAttempt() {
        AttemptLedger ledger = new AttemptLedger(); UUID id = UUID.randomUUID();
        long first = ledger.acquire(id); assertTrue(ledger.release(id, first)); long second = ledger.acquire(id);
        assertFalse(ledger.release(id, first)); assertTrue(ledger.owns(id, second));
    }
    @Test void releaseCurrentCancelsPreResolutionOwnership() {
        AttemptLedger ledger = new AttemptLedger(); UUID id = UUID.randomUUID(); ledger.acquire(id);
        assertTrue(ledger.releaseCurrent(id)); assertFalse(ledger.isActive(id));
    }
    @Test void clearDropsAllRuntimeOwnership() {
        AttemptLedger ledger = new AttemptLedger(); ledger.acquire(UUID.randomUUID()); ledger.acquire(UUID.randomUUID());
        assertEquals(2, ledger.size()); ledger.clear(); assertEquals(0, ledger.size());
    }
}
