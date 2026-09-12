package com.plexon.travel;

import java.util.random.RandomGenerator;

final class RtpGeometry {
    private RtpGeometry() {}

    static Point sampleAnnulus(double centerX, double centerZ, double minRadius, double maxRadius, RandomGenerator random) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) throw new IllegalArgumentException("center must be finite");
        if (!Double.isFinite(minRadius) || !Double.isFinite(maxRadius) || minRadius < 0D || maxRadius <= minRadius) {
            throw new IllegalArgumentException("invalid RTP radius");
        }
        double minSquared = minRadius * minRadius;
        double maxSquared = maxRadius * maxRadius;
        double radius = Math.sqrt(minSquared + random.nextDouble() * (maxSquared - minSquared));
        double angle = random.nextDouble() * Math.PI * 2D;
        int x = safeFloor(centerX + Math.cos(angle) * radius);
        int z = safeFloor(centerZ + Math.sin(angle) * radius);
        return new Point(x, z);
    }

    static Point sampleRectangle(Bounds bounds, RandomGenerator random) {
        if (bounds == null || bounds.empty()) throw new IllegalArgumentException("rectangle is empty");
        double x = bounds.minX() + random.nextDouble() * (bounds.maxX() - bounds.minX());
        double z = bounds.minZ() + random.nextDouble() * (bounds.maxZ() - bounds.minZ());
        return new Point(safeFloor(x), safeFloor(z));
    }

    static Bounds intersect(Bounds first, Bounds second) {
        if (first == null || second == null) return Bounds.emptyBounds();
        return new Bounds(Math.max(first.minX(), second.minX()), Math.min(first.maxX(), second.maxX()),
            Math.max(first.minZ(), second.minZ()), Math.min(first.maxZ(), second.maxZ()));
    }

    static boolean annulusIntersects(double centerX, double centerZ, double minRadius, double maxRadius, Bounds bounds) {
        if (bounds == null || bounds.empty()) return false;
        double nearestX = clamp(centerX, bounds.minX(), bounds.maxX());
        double nearestZ = clamp(centerZ, bounds.minZ(), bounds.maxZ());
        double nearest = Math.hypot(nearestX - centerX, nearestZ - centerZ);
        double farthest = Math.max(
            Math.max(Math.hypot(bounds.minX() - centerX, bounds.minZ() - centerZ), Math.hypot(bounds.minX() - centerX, bounds.maxZ() - centerZ)),
            Math.max(Math.hypot(bounds.maxX() - centerX, bounds.minZ() - centerZ), Math.hypot(bounds.maxX() - centerX, bounds.maxZ() - centerZ))
        );
        return nearest <= maxRadius && farthest >= minRadius;
    }

    static Bounds padded(Bounds bounds, double padding) {
        if (bounds == null || bounds.empty() || !Double.isFinite(padding) || padding < 0D) return Bounds.emptyBounds();
        return new Bounds(bounds.minX() + padding, bounds.maxX() - padding, bounds.minZ() + padding, bounds.maxZ() - padding);
    }

    private static int safeFloor(double value) {
        double limited = clamp(value, -29_999_984D, 29_999_984D);
        return (int) Math.floor(limited);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record Point(int x, int z) {
        double distanceFrom(double centerX, double centerZ) {
            return Math.hypot(x - centerX, z - centerZ);
        }
    }

    record Bounds(double minX, double maxX, double minZ, double maxZ) {
        static Bounds emptyBounds() {
            return new Bounds(0D, 0D, 0D, 0D);
        }

        boolean empty() {
            return !Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                || maxX <= minX || maxZ <= minZ;
        }

        boolean contains(double x, double z) {
            return !empty() && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
