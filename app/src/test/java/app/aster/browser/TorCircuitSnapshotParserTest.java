package app.aster.browser;

import org.junit.Test;

import static org.junit.Assert.*;

public class TorCircuitSnapshotParserTest {
    @Test public void selectsCircuitCurrentlyServingTheRequestedHost() {
        String circuits = """
                8 BUILT $AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA~GuardOne,$BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB~MiddleOne,$CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC~ExitOne PURPOSE=GENERAL TIME_CREATED=2026-10-26T13:00:00.000000
                9 BUILT $DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD~OtherGuard,$EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE~OtherExit PURPOSE=GENERAL TIME_CREATED=2026-10-26T13:10:00.000000
                10 BUILT $FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF~Hidden PURPOSE=HS_CLIENT_INTRO TIME_CREATED=2026-10-26T13:20:00.000000
                """;
        String streams = "22 SUCCEEDED 8 check.torproject.org:443 SOCKS_USERNAME=\"\"";

        TorCircuitSnapshotParser.Route route = TorCircuitSnapshotParser.select(circuits, streams, "https://check.torproject.org/api/ip");

        assertNotNull(route);
        assertEquals("8", route.circuitId);
        assertTrue(route.siteCircuit);
        assertEquals(3, route.relays.size());
        assertEquals("Guard", route.relays.get(0).role(0, 3));
        assertEquals("Middle", route.relays.get(1).role(1, 3));
        assertEquals("Exit", route.relays.get(2).role(2, 3));
        assertEquals("GuardOne", route.relays.get(0).nickname);
    }

    @Test public void fallsBackToMostRecentGeneralCircuitWithoutClaimingSiteAssociation() {
        String circuits = """
                4 BUILT $AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA~OldExit PURPOSE=GENERAL TIME_CREATED=2026-10-26T13:00:00.000000
                5 BUILT $BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB~NewestExit PURPOSE=GENERAL TIME_CREATED=2026-10-26T13:10:00.000000
                """;
        TorCircuitSnapshotParser.Route route = TorCircuitSnapshotParser.select(circuits, "", "example.com");
        assertNotNull(route);
        assertEquals("5", route.circuitId);
        assertFalse(route.siteCircuit);
        assertEquals("NewestExit", route.relays.get(0).nickname);
    }

    @Test public void returnsNoRouteWhenTorHasNoBuiltGeneralCircuit() {
        assertNull(TorCircuitSnapshotParser.select(
                "2 EXTENDED $AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA~Guard PURPOSE=GENERAL",
                "", "example.com"));
    }

    @Test public void readsRelayAddressFromConsensusRouterLineWithoutUsingPublishedTime() {
        assertEquals("192.0.2.7", TorCircuitSnapshotParser.routerAddress(
                "r RelayName AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB 2026-10-26 13:42:00 192.0.2.7 9001 0"));
        assertEquals("2001:db8::7", TorCircuitSnapshotParser.routerAddress(
                "r RelayName AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB 2026-10-26 13:42:00 2001:db8::7 9001 0"));
        assertEquals("", TorCircuitSnapshotParser.routerAddress("r short line"));
    }
}
