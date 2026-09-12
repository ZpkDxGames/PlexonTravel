package com.plexon.travel;

enum RtpBoundaryMode {
    ANNULUS,
    RECTANGLE,
    WORLD_BORDER
}

enum RtpCenterMode {
    WORLD_SPAWN,
    CONFIGURED
}

enum RtpProfileSource {
    EXPLICIT,
    LEGACY
}

record RtpProfile(
    boolean enabled,
    RtpBoundaryMode boundaryMode,
    RtpCenterMode centerMode,
    double centerX,
    double centerZ,
    double minRadius,
    double maxRadius,
    double minX,
    double maxX,
    double minZ,
    double maxZ,
    double worldBorderPadding,
    int maxAttempts,
    boolean generateChunks,
    RtpProfileSource source
) {
    RtpProfile explicit() {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, generateChunks, RtpProfileSource.EXPLICIT);
    }

    RtpProfile withEnabled(boolean value) {
        return new RtpProfile(value, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, generateChunks, source);
    }

    RtpProfile withMode(RtpBoundaryMode value) {
        return new RtpProfile(enabled, value, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, generateChunks, source);
    }

    RtpProfile withCenter(RtpCenterMode mode, double x, double z) {
        return new RtpProfile(enabled, boundaryMode, mode, x, z, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, generateChunks, source);
    }

    RtpProfile withRadius(double min, double max) {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, min, max,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, generateChunks, source);
    }

    RtpProfile withBounds(double newMinX, double newMaxX, double newMinZ, double newMaxZ) {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            newMinX, newMaxX, newMinZ, newMaxZ, worldBorderPadding, maxAttempts, generateChunks, source);
    }

    RtpProfile withPadding(double value) {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, value, maxAttempts, generateChunks, source);
    }

    RtpProfile withAttempts(int value) {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, value, generateChunks, source);
    }

    RtpProfile withGenerateChunks(boolean value) {
        return new RtpProfile(enabled, boundaryMode, centerMode, centerX, centerZ, minRadius, maxRadius,
            minX, maxX, minZ, maxZ, worldBorderPadding, maxAttempts, value, source);
    }
}

enum VoidDestinationMode {
    PLEXON_SPAWN,
    PLEXON_HUB,
    WORLD_SPAWN,
    FIXED
}

record VoidDestination(
    VoidDestinationMode mode,
    String targetWorld,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    static VoidDestination plexonSpawn() {
        return new VoidDestination(VoidDestinationMode.PLEXON_SPAWN, "", 0.5D, 100D, 0.5D, 0F, 0F);
    }

    static VoidDestination ofMode(VoidDestinationMode mode) {
        return new VoidDestination(mode, "", 0.5D, 100D, 0.5D, 0F, 0F);
    }

    static VoidDestination fixed(Destination destination) {
        return new VoidDestination(VoidDestinationMode.FIXED, destination.worldName(), destination.x(), destination.y(),
            destination.z(), destination.yaw(), destination.pitch());
    }
}

record VoidRescueRule(
    boolean enabled,
    double triggerY,
    VoidDestination destination,
    boolean rescueCreative
) {
    VoidRescueRule withEnabled(boolean value) {
        return new VoidRescueRule(value, triggerY, destination, rescueCreative);
    }

    VoidRescueRule withTriggerY(double value) {
        return new VoidRescueRule(enabled, value, destination, rescueCreative);
    }

    VoidRescueRule withDestination(VoidDestination value) {
        return new VoidRescueRule(enabled, triggerY, value, rescueCreative);
    }

    VoidRescueRule withRescueCreative(boolean value) {
        return new VoidRescueRule(enabled, triggerY, destination, value);
    }
}

record WorldTravelRule(String lastKnownName, RtpProfile rtp, VoidRescueRule voidRescue) {
    WorldTravelRule withRtp(RtpProfile value) {
        return new WorldTravelRule(lastKnownName, value, voidRescue);
    }

    WorldTravelRule withVoidRescue(VoidRescueRule value) {
        return new WorldTravelRule(lastKnownName, rtp, value);
    }

    boolean empty() {
        return rtp == null && voidRescue == null;
    }
}
