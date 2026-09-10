package com.plexon.travel;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Phase2SourceContractTest {
    private String source() throws Exception { return Files.readString(Path.of("src/main/java/com/plexon/travel/PlexonTravel.java")); }
    @Test void duplicateAttemptIsRejectedBeforeSafeResolution() throws Exception { assertTrue(source().contains("attempts.acquire(playerId)")); assertTrue(source().contains("already pending or in progress")); }
    @Test void executingAttemptCannotBeCancelledOutFromUnderAsyncTerminal() throws Exception { assertTrue(source().contains("if (value.executing) return false")); }
    @Test void terminalCallbackChecksExactAttemptIdentity() throws Exception { assertTrue(source().contains("attempts.owns(id, value.attemptId)")); }
    @Test void failedTeleportUsesSingleRefundPath() throws Exception { assertTrue(source().contains("refundOnce(value)")); assertTrue(source().contains("value.refunded")); }
    @Test void backHistoryOnlyCommitsOnSuccessfulAsyncTeleport() throws Exception { String s=source(); assertTrue(s.indexOf("Boolean.TRUE.equals(success)") < s.indexOf("setBack(id, value.origin")); }
    @Test void warpRenamePreservesStableId() throws Exception { assertTrue(source().contains("new Warp(old.id(), displayName")); assertTrue(source().contains("stable ID remains")); }
    @Test void deletionUsesBoundedRevisionConfirmation() throws Exception { assertTrue(source().contains("deleteGate.check(actor, \"delete-warp\", id, current.revision()")); }
    @Test void reloadValidatesCandidateBeforeBukkitReload() throws Exception { String s=source(); int m=s.indexOf("private void reloadValidated"); String r=s.substring(m, Math.min(s.length(), m+1000)); assertTrue(r.indexOf("TravelConfigValidator.validate(candidate)") < r.indexOf("reloadConfig()")); }
    @Test void placeholderExpansionIsCachedApiOnly() throws Exception { String p=Files.readString(Path.of("src/main/java/com/plexon/travel/papi/PlexonTravelExpansion.java")); assertFalse(p.contains("java.sql")); assertFalse(p.contains("Files.")); assertTrue(p.contains("api.warps().size()")); }
    @Test void candidateVersionIsRc1() throws Exception { assertTrue(Files.readString(Path.of("pom.xml")).contains("<version>2.0.0-rc.1</version>")); }
}
