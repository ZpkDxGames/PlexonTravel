package com.plexon.travel;

import com.plexon.travel.internal.TravelConfigValidator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TravelConfigValidatorTest {
    private YamlConfiguration valid() {
        YamlConfiguration c = new YamlConfiguration();
        for (String type : new String[]{"spawn","hub","warp","back","admin"}) { c.set("teleport."+type+".warmup-seconds", 3); c.set("teleport."+type+".cooldown-seconds", 10); c.set(type.equals("warp") ? "teleport.warp.default-fee" : "teleport."+type+".fee", 0.0); }
        c.set("teleport.movement-threshold", 0.01); c.set("safe-teleport.horizontal-radius", 3); c.set("safe-teleport.vertical-radius", 4); c.set("cooldowns.mode", "PER_TYPE"); c.set("hub.mode", "SEPARATE"); c.set("respawn.mode", "VANILLA"); return c;
    }
    @Test void acceptsDefaultShape() { assertTrue(TravelConfigValidator.validate(valid()).isEmpty()); }
    @Test void rejectsNegativeFee() { var c=valid(); c.set("teleport.spawn.fee", -1); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsNonFiniteMovementThreshold() { var c=valid(); c.set("teleport.movement-threshold", Double.NaN); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsOutOfBoundsSafeSearch() { var c=valid(); c.set("safe-teleport.horizontal-radius", 99); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
    @Test void rejectsUnknownModes() { var c=valid(); c.set("cooldowns.mode", "mystery"); assertFalse(TravelConfigValidator.validate(c).isEmpty()); }
}
