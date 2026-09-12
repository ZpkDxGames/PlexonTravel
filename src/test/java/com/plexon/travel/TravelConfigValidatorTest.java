package com.plexon.travel;

import com.plexon.travel.internal.TravelConfigValidator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelConfigValidatorTest {
    private YamlConfiguration valid() {
        YamlConfiguration c = new YamlConfiguration();
        for (String type : new String[]{"spawn", "hub", "warp", "back", "admin"}) {
            c.set("teleport." + type + ".warmup-seconds", 5);
            c.set("teleport." + type + ".cooldown-seconds", 10);
            c.set(type.equals("warp") ? "teleport.warp.default-fee" : "teleport." + type + ".fee", 0.0);
        }
        c.set("teleport.movement-threshold", 0.01);
        c.set("safe-teleport.horizontal-radius", 3);
        c.set("safe-teleport.vertical-radius", 4);
        c.set("cooldowns.mode", "PER_TYPE");
        c.set("hub.mode", "PER_WORLD");
        c.set("respawn.mode", "VANILLA");
        c.set("rtp.min-radius", 2000.0);
        c.set("rtp.max-radius", 10000.0);
        c.set("rtp.max-attempts", 24);
        c.set("rtp.center.mode", "WORLD_SPAWN");
        c.set("tpa.request-timeout-seconds", 30);
        return c;
    }

    private YamlConfiguration resource(String name) throws Exception {
        var stream = Objects.requireNonNull(getClass().getResourceAsStream(name), "Missing test resource " + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test void acceptsDefaultShape() { assertTrue(TravelConfigValidator.validate(valid()).isEmpty()); }
    @Test void acceptsMissingPathsThatWillInheritEmbeddedDefaults() {
        assertTrue(TravelConfigValidator.validate(new YamlConfiguration()).isEmpty());
    }
    @Test void acceptsLegacy2xShapeWithoutNewOptionalKeys() {
        var c = valid();
        c.set("hub.mode", "SEPARATE");
        c.set("rtp", null);
        c.set("tpa", null);
        assertTrue(TravelConfigValidator.validate(c).isEmpty());
    }
    @Test void acceptsActualLegacy2xFileWithEmbedded3xDefaults() throws Exception {
        var legacy = resource("/legacy-2.0.0-config.yml");
        var defaults = resource("/config.yml");
        legacy.setDefaults(defaults);

        assertFalse(legacy.isSet("destinations.spawn.fallback"));
        assertFalse(legacy.isSet("destinations.hub.fallback"));
        assertFalse(legacy.isSet("rtp.center.mode"));
        assertTrue(legacy.contains("destinations.spawn.fallback"));
        assertTrue(legacy.contains("destinations.hub.fallback"));
        assertTrue(legacy.contains("rtp.center.mode"));
        assertEquals("VANILLA", legacy.getString("destinations.spawn.fallback"));
        assertEquals("GLOBAL", legacy.getString("destinations.hub.fallback"));
        assertEquals("WORLD_SPAWN", legacy.getString("rtp.center.mode"));
        assertTrue(TravelConfigValidator.validate(legacy).isEmpty(),
            () -> String.join("; ", TravelConfigValidator.validate(legacy)));
    }
    @Test void rejectsNegativeFee() { var c = valid(); c.set("teleport.spawn.fee", -1); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsNonFiniteMovementThreshold() { var c = valid(); c.set("teleport.movement-threshold", Double.NaN); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsOutOfBoundsSafeSearch() { var c = valid(); c.set("safe-teleport.horizontal-radius", 99); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsUnknownModes() { var c = valid(); c.set("cooldowns.mode", "mystery"); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsInvertedRtpRadius() { var c = valid(); c.set("rtp.min-radius", 12000); c.set("rtp.max-radius", 10000); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsExcessiveRtpAttempts() { var c = valid(); c.set("rtp.max-attempts", 100); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
}
