package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelDestinationView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PlexonTravelModelTest {
    @Test
    void canonicalWarpIdsAreBoundedAndSafe() throws Exception {
        Method method = PlexonTravel.class.getDeclaredMethod("normalizeId", String.class);
        method.setAccessible(true);
        assertEquals("spawn-shop_2", method.invoke(null, " Spawn Shop_2!! "));
        String longValue = (String) method.invoke(null, "A".repeat(100));
        assertEquals(48, longValue.length());
    }

    @Test
    void publicDestinationViewIsImmutableValueData() {
        TravelDestinationView view = new TravelDestinationView(UUID.randomUUID(), "world", 1.5, 64, -2.5, 90F, 0F);
        assertNotNull(view.worldId());
        assertEquals("world", view.worldName());
        assertFalse(Double.isNaN(view.x()));
    }
}
