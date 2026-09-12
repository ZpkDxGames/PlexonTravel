package com.plexon.travel;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void annulusSamplingRemainsUniformByArea() {
        Random random = new Random(91L);
        double min = 100D;
        double max = 1000D;
        double sumSquared = 0D;
        int samples = 50_000;
        for (int i = 0; i < samples; i++) {
            RtpGeometry.Point point = RtpGeometry.sampleAnnulus(0D, 0D, min, max, random);
            double distance = point.distanceFrom(0D, 0D);
            sumSquared += distance * distance;
        }
        double observed = sumSquared / samples;
        double expected = (min * min + max * max) / 2D;
        assertEquals(expected, observed, expected * 0.015D);
    }

    @Test
    void rectangleSamplesStayWithinBounds() {
        RtpGeometry.Bounds bounds = new RtpGeometry.Bounds(-250D, 725D, 80D, 920D);
        Random random = new Random(7L);
        for (int i = 0; i < 20_000; i++) {
            RtpGeometry.Point point = RtpGeometry.sampleRectangle(bounds, random);
            assertTrue(point.x() >= -250 && point.x() < 725);
            assertTrue(point.z() >= 80 && point.z() < 920);
        }
    }

    @Test
    void intersectionAndPaddingFailClosedWhenEmpty() {
        RtpGeometry.Bounds first = new RtpGeometry.Bounds(-100D, 100D, -100D, 100D);
        RtpGeometry.Bounds second = new RtpGeometry.Bounds(50D, 200D, -40D, 40D);
        RtpGeometry.Bounds intersection = RtpGeometry.intersect(first, second);
        assertEquals(50D, intersection.minX());
        assertEquals(100D, intersection.maxX());
        assertEquals(-40D, intersection.minZ());
        assertEquals(40D, intersection.maxZ());
        assertFalse(intersection.empty());
        assertTrue(RtpGeometry.padded(first, 100D).empty());
    }

    @Test
    void annulusIntersectionDetectsImpossibleRegions() {
        RtpGeometry.Bounds near = new RtpGeometry.Bounds(-50D, 50D, -50D, 50D);
        RtpGeometry.Bounds far = new RtpGeometry.Bounds(5000D, 5100D, 5000D, 5100D);
        assertTrue(RtpGeometry.annulusIntersects(0D, 0D, 10D, 100D, near));
        assertFalse(RtpGeometry.annulusIntersects(0D, 0D, 10D, 100D, far));
    }

    @Test
    void rejectsInvertedRadiusAndRectangle() {
        assertThrows(IllegalArgumentException.class,
            () -> RtpGeometry.sampleAnnulus(0D, 0D, 10000D, 2000D, new Random(1L)));
        assertThrows(IllegalArgumentException.class,
            () -> RtpGeometry.sampleRectangle(new RtpGeometry.Bounds(10D, 0D, 0D, 10D), new Random(1L)));
    }
}
