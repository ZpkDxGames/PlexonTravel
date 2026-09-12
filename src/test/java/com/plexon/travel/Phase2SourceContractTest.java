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
        assertTrue(source.contains("runSync("));
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
    void safeResolverNeverSynchronouslyTouchesUnloadedChunks() throws Exception {
        String source = source("TravelEngine.java");
        assertTrue(source.contains("safe-teleport.generate-chunks"));
        assertTrue(source.contains("isChunkGenerated"));
        assertTrue(source.contains("getChunkAtAsync(chunkX, chunkZ, generate)"));
        assertTrue(source.contains("if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return false"));
    }

    @Test
    void rtpUsesBoundedCancelableAsyncChunkSearch() throws Exception {
        String source = source("RtpService.java");
        assertTrue(source.contains("profile.maxAttempts()"));
        assertTrue(source.contains("searching.contains(playerId)"));
        assertTrue(source.contains("isChunkGenerated"));
        assertTrue(source.contains("getChunkAtAsync"));
        assertTrue(source.contains("generateChunks()"));
        assertFalse(source.contains("while (true)"));
    }

    @Test
    void perWorldPolicyHotPathDoesNotParseYamlOrTouchDatabase() throws Exception {
        String rtp = source("RtpService.java");
        String rescue = source("VoidRescueService.java");
        assertFalse(rtp.contains("YamlConfiguration"));
        assertFalse(rtp.contains("java.sql"));
        assertFalse(rescue.contains("YamlConfiguration"));
        assertFalse(rescue.contains("java.sql"));
        assertTrue(rescue.contains("worldSettings.voidRule(world)"));
    }

    @Test
    void voidRescueIsDeduplicatedAndDoesNotUseNormalTravelRequest() throws Exception {
        String source = source("VoidRescueService.java");
        assertTrue(source.contains("if (!inFlight.add(playerId)) return"));
        assertTrue(source.contains("teleportAsync"));
        assertTrue(source.contains("internalTeleports.add(playerId)"));
        assertFalse(source.contains("engine.request("));
        assertFalse(source.contains("dispatchCommand"));
    }

    @Test
    void voidRescueCannotPolluteBackHistory() throws Exception {
        String plugin = source("PlexonTravel.java");
        assertTrue(plugin.contains("voidRescue.isInternal(playerId)"));
        assertTrue(plugin.contains("boolean rescueInFlight = voidRescue != null && voidRescue.isInFlight(playerId)"));
        assertTrue(plugin.contains("if (!rescueInFlight && destinations != null"));
    }

    @Test
    void tpaUsesSharedTravelEngineAndObservesTerminalResult() throws Exception {
        String source = source("TpaService.java");
        assertTrue(source.contains("TravelType.TPA"));
        assertTrue(source.contains("engine.request"));
        assertTrue(source.contains(".whenComplete((success, failure)"));
    }

    @Test
    void inventoryMenusUseDedicatedHolderAndAdventureTitles() throws Exception {
        String source = source("TravelMenus.java");
        assertTrue(source.contains("implements InventoryHolder"));
        assertTrue(source.contains("InventoryDragEvent"));
        assertTrue(source.contains("Bukkit.createInventory(holder, size, messages.raw(title))"));
        assertFalse(source.contains("Bukkit.createInventory(holder, size, messages.legacy"));
    }

    @Test
    void persistenceSkipsOnlyMalformedRows() throws Exception {
        String source = source("TravelStorage.java");
        assertTrue(source.contains("warnInvalidRow(\"destinations\""));
        assertTrue(source.contains("warnInvalidRow(\"warps\""));
        assertTrue(source.contains("warnInvalidRow(\"back_locations\""));
        assertTrue(source.contains("RejectedExecutionException"));
    }

    @Test
    void startupFailureIncludesPhaseAndPreservesFailedState() throws Exception {
        String source = source("PlexonTravel.java");
        assertTrue(source.contains("catch (Exception | LinkageError failure)"));
        assertTrue(source.contains("Unexpected startup failure during"));
        assertTrue(source.contains("if (!startupFailed)"));
        assertTrue(source.contains("STARTUP_PHASE="));
        assertTrue(source.contains("STARTUP_READY version="));
        assertTrue(source.contains("startup-failure.txt"));
        assertTrue(source.contains("worldSettings.load()"));
    }

    @Test
    void placeholderExpansionRemainsCachedApiOnly() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/plexon/travel/papi/PlexonTravelExpansion.java"));
        assertFalse(source.contains("java.sql"));
        assertFalse(source.contains("Files."));
        assertTrue(source.contains("api.warpCount()"));
    }

    @Test
    void releaseLineIs310OnCore205() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<version>3.1.0</version>"));
        assertTrue(pom.contains("<core.version>2.0.5</core.version>"));
    }
}
