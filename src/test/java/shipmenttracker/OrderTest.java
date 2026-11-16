package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tenants order shipments from any carrier; the carrier accepts or rejects. */
class OrderTest {

    private TestServer server;
    private TestServer.Account alice; // tenant acme
    private TestServer.Account bob;   // tenant globex
    private TestServer.Account dave;  // carrier fastfreight
    private TestServer.Account erin;  // carrier quickship

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        alice = server.tenantWithUser("acme", "alice");
        bob = server.tenantWithUser("globex", "bob");
        dave = server.carrierWithWorker("fastfreight", "dave");
        erin = server.carrierWithWorker("quickship", "erin");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void tenantsCanListCarriers() throws Exception {
        TestServer.Response r = server.get("/carriers", alice.token());
        assertEquals(200, r.status());
        assertEquals(List.of("fastfreight", "quickship"), names(r.body()));
        assertEquals(401, server.get("/carriers", null).status());
    }

    @Test
    void orderAcceptedThenDelivered() throws Exception {
        TestServer.Response placed = server.post("/shipments", alice.token(),
                Map.of("carrierId", dave.orgId(), "reference", "PO-1001"));
        assertEquals(201, placed.status(), placed.body().toString());
        assertEquals("ORDERED", placed.body().get("status").asText());
        assertTrue(placed.body().get("trackingNumber").isNull(), "no tracking number until accepted");
        assertEquals("PO-1001", placed.body().get("reference").asText());
        assertEquals(dave.orgId(), placed.body().get("carrierId").asLong());
        assertEquals(alice.orgId(), placed.body().get("tenantId").asLong());
        String id = placed.body().get("id").asText();

        // The chosen carrier sees the order; nobody else does.
        assertEquals(200, server.get("/shipments/" + id, dave.token()).status());
        assertEquals(404, server.get("/shipments/" + id, erin.token()).status());
        assertEquals(404, server.get("/shipments/" + id, bob.token()).status());

        // It can't be picked up before it's accepted.
        TestServer.Response early = server.setStatus(dave, id, "PICKED_UP");
        assertEquals(409, early.status());
        assertEquals(List.of("CREATED", "REJECTED"), names(early.body().get("allowed")));

        assertEquals(400, server.post("/shipments/" + id + "/accept", dave.token(), Map.of()).status(),
                "accepting needs a tracking number");
        TestServer.Response accepted = server.post("/shipments/" + id + "/accept", dave.token(),
                Map.of("trackingNumber", "FF123456789", "note", "pickup tomorrow 9am"));
        assertEquals(200, accepted.status(), accepted.body().toString());
        assertEquals("CREATED", accepted.body().get("status").asText());
        assertEquals("FF123456789", accepted.body().get("trackingNumber").asText());

        for (String s : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")) {
            assertEquals(200, server.setStatus(dave, id, s).status(), s);
        }

        JsonNode history = server.get("/shipments/" + id + "/history", alice.token()).body();
        List<String> steps = new ArrayList<>();
        history.forEach(h -> steps.add(h.get("status").asText() + ":" + h.get("by").asText()));
        assertEquals(List.of("ORDERED:alice", "CREATED:dave", "PICKED_UP:dave", "IN_TRANSIT:dave",
                "OUT_FOR_DELIVERY:dave", "DELIVERED:dave"), steps);
        assertEquals("pickup tomorrow 9am", history.get(1).get("note").asText());
    }

    @Test
    void carrierRejectsWithAReason() throws Exception {
        String id = server.placeOrder(alice, dave.orgId(), "PO-2");
        assertEquals(400, server.post("/shipments/" + id + "/reject", dave.token(), Map.of()).status(),
                "rejecting needs a reason");
        TestServer.Response rejected = server.post("/shipments/" + id + "/reject", dave.token(),
                Map.of("reason", "we don't serve that area"));
        assertEquals(200, rejected.status());
        assertEquals("REJECTED", rejected.body().get("status").asText());

        // The tenant sees why, and it's final.
        JsonNode history = server.get("/shipments/" + id + "/history", alice.token()).body();
        assertEquals("we don't serve that area", history.get(1).get("note").asText());
        assertEquals(409, server.accept(dave, id, "FF1").status());
        assertEquals(409, server.post("/shipments/" + id + "/cancel", alice.token(), Map.of()).status());
    }

    @Test
    void tenantCancelsWhileStillOrdered() throws Exception {
        String id = server.placeOrder(alice, dave.orgId(), "PO-3");
        TestServer.Response cancelled = server.post("/shipments/" + id + "/cancel", alice.token(),
                Map.of("reason", "ordered by mistake"));
        assertEquals(200, cancelled.status());
        assertEquals("CANCELLED", cancelled.body().get("status").asText());
        assertEquals(409, server.accept(dave, id, "FF1").status(), "a cancelled order can't be accepted");
    }

    @Test
    void tenantCannotCancelOnceAccepted() throws Exception {
        String id = server.placeOrder(alice, dave.orgId(), "PO-4");
        assertEquals(200, server.accept(dave, id, "FF1").status());
        TestServer.Response late = server.post("/shipments/" + id + "/cancel", alice.token(), Map.of());
        assertEquals(409, late.status());
        assertEquals(0, late.body().get("allowed").size());
    }

    @Test
    void onlyTheRightSideMakesEachMove() throws Exception {
        String id = server.placeOrder(alice, dave.orgId(), "PO-5");

        // Tenants can't accept or reject; carriers can't cancel.
        assertEquals(403, server.accept(alice, id, "X").status());
        assertEquals(403, server.post("/shipments/" + id + "/reject", alice.token(), Map.of("reason", "x")).status());
        assertEquals(403, server.post("/shipments/" + id + "/cancel", dave.token(), Map.of()).status());

        // Another carrier or tenant can't touch it at all, and can't tell it exists.
        assertEquals(404, server.accept(erin, id, "X").status());
        assertEquals(404, server.post("/shipments/" + id + "/cancel", bob.token(), Map.of()).status());

        // /status isn't a back door for order moves.
        assertEquals(400, server.setStatus(dave, id, "CREATED").status());
        assertEquals(400, server.setStatus(dave, id, "REJECTED").status());

        assertEquals("ORDERED", server.get("/shipments/" + id, alice.token()).body().get("status").asText());
    }

    @Test
    void tenantCanOrderFromAnyCarrier() throws Exception {
        server.placeOrder(alice, dave.orgId(), "PO-A");
        server.placeOrder(alice, erin.orgId(), "PO-B");
        assertEquals(2, server.get("/shipments", alice.token()).body().size());
        assertEquals(1, server.get("/shipments", dave.token()).body().size());
        assertEquals(1, server.get("/shipments", erin.token()).body().size());

        assertEquals(404, server.post("/shipments", alice.token(), Map.of("carrierId", 999)).status());
        assertEquals(400, server.post("/shipments", alice.token(), Map.of("reference", "PO-X")).status());
    }

    @Test
    void acceptAndCancelRaceHasOneWinner() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 10; round++) {
                String id = server.placeOrder(alice, dave.orgId(), "RACE-" + round);
                CountDownLatch go = new CountDownLatch(1);
                Future<Integer> accept = pool.submit(() -> {
                    go.await();
                    return server.accept(dave, id, "FF-" + id).status();
                });
                Future<Integer> cancel = pool.submit(() -> {
                    go.await();
                    return server.post("/shipments/" + id + "/cancel", alice.token(), Map.of()).status();
                });
                go.countDown();

                List<Integer> codes = List.of(accept.get(), cancel.get());
                assertTrue(codes.contains(200) && codes.contains(409), "round " + round + ": " + codes);
                String status = server.get("/shipments/" + id, alice.token()).body().get("status").asText();
                assertEquals(accept.get() == 200 ? "CREATED" : "CANCELLED", status);
                assertEquals(2, server.get("/shipments/" + id + "/history", alice.token()).body().size());
            }
        }
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isTextual() ? n.asText() : n.get("name").asText()));
        return out;
    }
}
