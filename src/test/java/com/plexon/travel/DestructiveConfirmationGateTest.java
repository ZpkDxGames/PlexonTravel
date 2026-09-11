package com.plexon.travel;

import com.plexon.travel.internal.DestructiveConfirmationGate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DestructiveConfirmationGateTest {
    @Test void firstInvocationOnlyArms() { var g = new DestructiveConfirmationGate(1000); assertEquals(DestructiveConfirmationGate.Decision.ARMED, g.check("a","delete","w",1,10)); }
    @Test void secondMatchingInvocationConfirmsOnce() { var g = new DestructiveConfirmationGate(1000); g.check("a","delete","w",1,10); assertEquals(DestructiveConfirmationGate.Decision.CONFIRMED, g.check("a","delete","w",1,11)); assertEquals(DestructiveConfirmationGate.Decision.ARMED, g.check("a","delete","w",1,12)); }
    @Test void actorIsBound() { var g = new DestructiveConfirmationGate(1000); g.check("a","delete","w",1,10); assertEquals(DestructiveConfirmationGate.Decision.ARMED, g.check("b","delete","w",1,11)); }
    @Test void revisionChangeInvalidatesConfirmation() { var g = new DestructiveConfirmationGate(1000); g.check("a","delete","w",1,10); assertEquals(DestructiveConfirmationGate.Decision.ARMED, g.check("a","delete","w",2,11)); }
    @Test void expiryInvalidatesConfirmation() { var g = new DestructiveConfirmationGate(1000); g.check("a","delete","w",1,10); assertEquals(DestructiveConfirmationGate.Decision.ARMED, g.check("a","delete","w",1,1011)); }
}
