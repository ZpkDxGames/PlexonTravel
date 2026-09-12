package com.plexon.travel;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpGeometryTest {
    @Test
    void samplesStayInsideConfiguredAnnulus() {
        Random random = new Random(42L);
        double min = 2000D;
        double max = 10000D;
        for (int i = 0; i < 10_000; i++) {
            RtpGeometry.Point point = RtpGeometry.sampleAnnulus(0D, 0D, min, max, random);
            double distance = point.distanceFrom(0D, 0D);
            assertTrue(distance >= min - 1.5D, "sample breached minimum radius: " + distance);
            assertTrue(distance <= max + 1.5D, "sample breached maximum radius: " + distance);
        }
    }

    @Test
    void rejectsInvertedRadius() {
        assertThrows(IllegalArgumentException.class,
            () -> RtpGeometry.sampleAnnulus(0D, 0D, 10000D, 2000D, new Random(1L)));
    }
}
