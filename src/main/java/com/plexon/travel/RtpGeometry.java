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
        int x = (int) Math.floor(centerX + Math.cos(angle) * radius);
        int z = (int) Math.floor(centerZ + Math.sin(angle) * radius);
        return new Point(x, z);
    }

    record Point(int x, int z) {
        double distanceFrom(double centerX, double centerZ) {
            return Math.hypot(x - centerX, z - centerZ);
        }
    }
}
