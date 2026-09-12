package com.plexon.travel;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2SourceContractTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/plexon/travel/" + name));
    }

    @Test
    void teleportEngineRetainsAttemptIdentityAndAsyncTerminalSafety() throws Exception {
        String source = source("TravelEngine.java");
        assertTrue(source.contains("attempts.acquire(playerId)"));
        assertTrue(source.contains("if (value.executing) return false"));
        assertTrue(source.contains("attempts.owns(playerId, value.attemptId)"));
        assertTrue(source.contains("teleportAsync"));
        assertTrue(source.contains("refundOnce(value)"));
    }

    @Test
    void playerExperienceIncludesBossbarAndParticles() throws Exception {
        String source = source("TravelEngine.java");
        assertTrue(source.contains("Bukkit.createBossBar"));
        assertTrue(source.contains("Particle.PORTAL"));
        assertTrue(source.contains("Particle.END_ROD"));
    }

    @Test
    void destinationsArePerWorldWithLegacyFallback() throws Exception {
        String source = source("DestinationRegistry.java");
        assertTrue(source.contains("spawn:"));
        assertTrue(source.contains("hub:"));
        assertTrue(source.contains("legacy-global-fallback"));
    }

    @Test
    void rtpUsesBoundedAsyncChunkSearchWithoutForcedGenerationByDefault() throws Exception {
        String source = source("RtpService.java");
        assertTrue(source.contains("max-attempts"));
        assertTrue(source.contains("isChunkGenerated"));
        assertTrue(source.contains("getChunkAtAsync"));
        assertTrue(source.contains("generate-chunks"));
    }

    @Test
    void tpaUsesSharedTravelEngine() throws Exception {
        String source = source("TpaService.java");
        assertTrue(source.contains("TravelType.TPA"));
        assertTrue(source.contains("engine.request"));
    }

    @Test
    void inventoryMenusUseDedicatedHolderIdentification() throws Exception {
        String source = source("TravelMenus.java");
        assertTrue(source.contains("implements InventoryHolder"));
        assertTrue(source.contains("InventoryDragEvent"));
    }

    @Test
    void placeholderExpansionRemainsCachedApiOnly() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/plexon/travel/papi/PlexonTravelExpansion.java"));
        assertFalse(source.contains("java.sql"));
        assertFalse(source.contains("Files."));
        assertTrue(source.contains("api.warps().size()"));
    }

    @Test
    void releaseLineIs300OnCore205() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<version>3.0.0</version>"));
        assertTrue(pom.contains("<core.version>2.0.5</core.version>"));
    }
}
