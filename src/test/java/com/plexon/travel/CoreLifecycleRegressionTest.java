package com.plexon.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CoreLifecycleRegressionTest {
    private static final ModuleVersionRange SUPPORTED = ModuleVersionRange.parse(">=2.0 <3.0");

    @Test
    void acceptsCore204AndRejectsCore3() {
        assertTrue(SUPPORTED.contains(CoreVersion.of(2, 0, "2.0.4")));
        assertFalse(SUPPORTED.contains(CoreVersion.of(3, 0, "3.0.0")));
    }

    @Test
    void sourceFailsClosedWhenCoreServiceIsMissing() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/plexon/travel/PlexonTravel.java"));
        assertTrue(source.contains("coreRegistration == null || coreRegistration.getProvider() == null"));
        assertTrue(source.contains("disablePlugin(this)"));
    }

    @Test
    void startingTransitionsToReadyOnlyForExactOwner() {
        ModuleRegistry registry = new ModuleRegistry(CoreVersion.of(2, 0, "2.0.4"));
        Plugin owner = plugin("PlexonTravel", true);
        assertTrue(registry.register(descriptor(owner, ModuleState.STARTING)).success());
        assertEquals(ModuleState.STARTING, registry.find("travel").orElseThrow().state());
        assertTrue(registry.updateState("travel", owner, ModuleState.READY, "ready"));
        assertEquals(ModuleState.READY, registry.find("travel").orElseThrow().state());
    }

    @Test
    void duplicateLiveOwnerCannotOverwriteMutateOrRemoveModule() {
        ModuleRegistry registry = new ModuleRegistry(CoreVersion.of(2, 0, "2.0.4"));
        Plugin first = plugin("PlexonTravel", true);
        Plugin second = plugin("PlexonTravel", true);
        assertTrue(registry.register(descriptor(first, ModuleState.STARTING)).success());
        assertFalse(registry.register(descriptor(second, ModuleState.STARTING)).success());
        assertFalse(registry.updateState("travel", second, ModuleState.READY, "late callback"));
        assertEquals(0, registry.unregisterOwnedBy(second));
        assertSame(first, registry.find("travel").orElseThrow().plugin());
        assertEquals(1, registry.unregisterOwnedBy(first));
        assertTrue(registry.find("travel").isEmpty());
    }

    private static ModuleDescriptor descriptor(Plugin plugin, ModuleState state) {
        return new ModuleDescriptor(
            "travel", "PlexonTravel", plugin.getName(), "2.0.0-rc.1", plugin, SUPPORTED,
            Set.of("safe-teleport"), state, "test", Instant.now());
    }

    private static Plugin plugin(String name, boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> enabled;
                case "toString" -> name + "Proxy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
