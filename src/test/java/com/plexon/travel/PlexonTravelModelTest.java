package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PlexonTravelModelTest {
    @Test
    void canonicalWarpIdsAreBoundedAndSafe() {
        assertEquals("spawn-shop_2", DestinationRegistry.normalizeId(" Spawn Shop_2!! "));
        String longValue = DestinationRegistry.normalizeId("A".repeat(100));
        assertEquals(48, longValue.length());
    }

    @Test
    void publicDestinationViewIsImmutableValueData() {
        TravelDestinationView view = new TravelDestinationView(UUID.randomUUID(), "world", 1.5, 64, -2.5, 90F, 0F);
        assertNotNull(view.worldId());
        assertEquals("world", view.worldName());
        assertFalse(Double.isNaN(view.x()));
    }

    @Test
    void tpaRequestExpiresAtItsBoundedDeadline() {
        TpaRequest request = new TpaRequest(UUID.randomUUID(), UUID.randomUUID(), TpaMode.TO_TARGET, 1_000L, 31_000L);
        assertFalse(request.expired(30_999L));
        assertEquals(true, request.expired(31_000L));
    }
}
