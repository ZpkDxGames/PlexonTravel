package com.plexon.travel;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSettingsModelTest {
    @Test
    void cleanDefaultsAndEmptyLegacyCompatibleFileAreAccepted() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        WorldSettingsManager.ParseResult result = WorldSettingsManager.parse(yaml, WorldSettingsManager.LegacyRtpDefaults.testDefaults());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.rules().isEmpty());
    }

    @Test
    void explicitPerWorldRtpOverrideParsesIndependentlyOfLegacyDefaults() {
        UUID worldId = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        String base = "worlds." + worldId;
        yaml.set(base + ".name", "Survival_World");
        yaml.set(base + ".rtp.enabled", true);
        yaml.set(base + ".rtp.boundary.mode", "RECTANGLE");
        yaml.set(base + ".rtp.boundary.rectangle.min-x", -5000D);
        yaml.set(base + ".rtp.boundary.rectangle.max-x", 5000D);
        yaml.set(base + ".rtp.boundary.rectangle.min-z", -2500D);
        yaml.set(base + ".rtp.boundary.rectangle.max-z", 2500D);
        yaml.set(base + ".rtp.max-attempts", 31);
        yaml.set(base + ".rtp.generate-chunks", false);

        WorldSettingsManager.ParseResult result = WorldSettingsManager.parse(yaml, WorldSettingsManager.LegacyRtpDefaults.testDefaults());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        RtpProfile profile = result.rules().get(worldId).rtp();
        assertEquals(RtpBoundaryMode.RECTANGLE, profile.boundaryMode());
        assertEquals(-5000D, profile.minX());
        assertEquals(5000D, profile.maxX());
        assertEquals(31, profile.maxAttempts());
        assertEquals(RtpProfileSource.EXPLICIT, profile.source());
    }

    @Test
    void malformedRectangleAndUnknownModeFailClosed() {
        RtpProfile inverted = new RtpProfile(true, RtpBoundaryMode.RECTANGLE, RtpCenterMode.WORLD_SPAWN,
            0D, 0D, 2000D, 10000D, 100D, -100D, -50D, 50D, 16D, 24, false, RtpProfileSource.EXPLICIT);
        List<String> errors = WorldSettingsManager.validateRtp(inverted, "rtp");
        assertFalse(errors.isEmpty());

        UUID worldId = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.set("worlds." + worldId + ".rtp.boundary.mode", "HEXAGON");
        WorldSettingsManager.ParseResult parsed = WorldSettingsManager.parse(yaml, WorldSettingsManager.LegacyRtpDefaults.testDefaults());
        assertFalse(parsed.errors().isEmpty());
        assertNull(parsed.rules().get(worldId).rtp().boundaryMode());
    }

    @Test
    void invalidRadiusAndAttemptCountAreRejected() {
        RtpProfile profile = new RtpProfile(true, RtpBoundaryMode.ANNULUS, RtpCenterMode.CONFIGURED,
            0D, 0D, 10000D, 2000D, -100D, 100D, -100D, 100D, 16D, 65, false, RtpProfileSource.EXPLICIT);
        List<String> errors = WorldSettingsManager.validateRtp(profile, "rtp");
        assertTrue(errors.stream().anyMatch(value -> value.contains("max-radius")));
        assertTrue(errors.stream().anyMatch(value -> value.contains("max-attempts")));
    }

    @Test
    void voidRulesValidateFiniteThresholdAndFixedDestination() {
        VoidRescueRule valid = new VoidRescueRule(true, -60D,
            new VoidDestination(VoidDestinationMode.FIXED, "Survival_World", 0.5D, 100D, 0.5D, 0F, 0F), false);
        assertTrue(WorldSettingsManager.validateVoid(valid, "void-rescue").isEmpty());

        VoidRescueRule invalid = new VoidRescueRule(true, Double.NaN,
            new VoidDestination(VoidDestinationMode.FIXED, "Survival_World", Double.POSITIVE_INFINITY, 100D, 0.5D, 0F, 0F), false);
        assertFalse(WorldSettingsManager.validateVoid(invalid, "void-rescue").isEmpty());
    }

    @Test
    void resetModelsPreserveLegacyAndDefaultSemantics() {
        WorldSettingsManager.LegacyRtpDefaults legacy = WorldSettingsManager.LegacyRtpDefaults.testDefaults();
        assertTrue(legacy.enabled());
        assertEquals(RtpCenterMode.WORLD_SPAWN, legacy.centerMode());
        assertEquals(2000D, legacy.minRadius());
        assertEquals(10000D, legacy.maxRadius());
        assertEquals(24, legacy.maxAttempts());
    }
}
