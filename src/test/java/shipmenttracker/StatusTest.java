package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Carrier workers pick up shipments and move them through the steps. */
class StatusTest {

    private TestServer server;
    private TestServer.Account acme;
    private TestServer.Account dave;
    private TestServer.Account frank;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        acme = server.tenantWithUser("acme", "alice");
        dave = server.carrierWithWorker("fastfreight", "dave");
        frank = server.addUser("carrierId", dave.orgId(), "frank");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void fullDeliveryWithHistory() throws Exception {
        String id = server.createShipment(dave, acme.orgId(), "ACME-1");

        TestServer.Response picked = server.setStatus(dave, id, "PICKED_UP");
        assertEquals(200, picked.status(), picked.body().toString());
        assertEquals("PICKED_UP", picked.body().get("status").asText());
        long daveId = picked.body().get("pickedUpBy").asLong();

        // Another worker from the same carrier can carry on with it.
        assertEquals(200, server.setStatus(frank, id, "IN_TRANSIT").status());
        assertEquals(200, server.setStatus(frank, id, "OUT_FOR_DELIVERY").status());
        TestServer.Response delivered = server.post("/shipments/" + id + "/status", frank.token(),
                Map.of("status", "DELIVERED", "note", "left at front door"));
        assertEquals(200, delivered.status());
        assertEquals("DELIVERED", delivered.body().get("status").asText());
        assertEquals(daveId, delivered.body().get("pickedUpBy").asLong(), "pickedUpBy stays the first worker");

        // The tenant sees the final status and every step, with who did it.
        assertEquals("DELIVERED", server.get("/shipments/" + id, acme.token()).body().get("status").asText());
        JsonNode history = server.get("/shipments/" + id + "/history", acme.token()).body();
        assertEquals(List.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"), statuses(history));
        assertEquals(List.of("dave", "dave", "frank", "frank", "frank"), actors(history));
        assertEquals("left at front door", history.get(4).get("note").asText());
        assertTrue(history.get(0).get("note").isNull());
    }

    @Test
    void failedDeliveryCanBeRetriedOrReturned() throws Exception {
        String retried = server.createShipment(dave, acme.orgId(), "RETRY");
        for (String s : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERY_FAILED",
                "OUT_FOR_DELIVERY", "DELIVERED")) {
            assertEquals(200, server.setStatus(dave, retried, s).status(), s);
        }

        String returned = server.createShipment(dave, acme.orgId(), "RETURN");
        for (String s : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERY_FAILED", "RETURNED")) {
            assertEquals(200, server.setStatus(dave, returned, s).status(), s);
        }
    }

    @Test
    void stepsCannotBeSkippedOrReversed() throws Exception {
        String id = server.createShipment(dave, acme.orgId(), "ACME-1");

        TestServer.Response skip = server.setStatus(dave, id, "DELIVERED");
        assertEquals(409, skip.status());
        assertEquals("CREATED", skip.body().get("current").asText());
        assertEquals("PICKED_UP", skip.body().get("allowed").get(0).asText());

        server.setStatus(dave, id, "PICKED_UP");
        assertEquals(400, server.setStatus(dave, id, "CREATED").status(), "CREATED is only reached via /accept");
        assertEquals(409, server.setStatus(dave, id, "PICKED_UP").status(), "can't pick up twice");
        server.setStatus(dave, id, "IN_TRANSIT");
        assertEquals(409, server.setStatus(dave, id, "PICKED_UP").status(), "can't go back a step");

        // A refused move leaves no trace in the history.
        assertEquals(List.of("CREATED", "PICKED_UP", "IN_TRANSIT"),
                statuses(server.get("/shipments/" + id + "/history", dave.token()).body()));
    }

    @Test
    void deliveredAndReturnedAreFinal() {
        assertTrue(ShipmentStatus.DELIVERED.next().isEmpty());
        assertTrue(ShipmentStatus.RETURNED.next().isEmpty());
        // Only an order being accepted can become CREATED; nothing goes back to it.
        for (ShipmentStatus s : ShipmentStatus.values()) {
            if (s != ShipmentStatus.ORDERED) {
                assertFalse(s.canMoveTo(ShipmentStatus.CREATED), s + " -> CREATED");
            }
            assertFalse(s.canMoveTo(ShipmentStatus.ORDERED), s + " -> ORDERED");
        }
    }

    @Test
    void rejectsUnknownStatusAndLongNote() throws Exception {
        String id = server.createShipment(dave, acme.orgId(), "ACME-1");
        assertEquals(400, server.setStatus(dave, id, "LOST_IN_SPACE").status());
        assertEquals(400, server.setStatus(dave, id, "CANCELLED").status(), "orders use /cancel");
        assertEquals(400, server.post("/shipments/" + id + "/status", dave.token(), Map.of()).status());
        assertEquals(400, server.post("/shipments/" + id + "/status", dave.token(),
                Map.of("status", "PICKED_UP", "note", "x".repeat(501))).status());
    }

    @Test
    void tenantsCanWatchButNotMove() throws Exception {
        String id = server.createShipment(dave, acme.orgId(), "ACME-1");
        assertEquals(403, server.setStatus(acme, id, "PICKED_UP").status());

        // Another tenant gets 404, not 403, so it can't tell the shipment exists.
        TestServer.Account globex = server.tenantWithUser("globex", "bob");
        assertEquals(404, server.setStatus(globex, id, "PICKED_UP").status());

        assertEquals("CREATED", server.get("/shipments/" + id, acme.token()).body().get("status").asText());
    }

    private static List<String> statuses(JsonNode history) {
        return stream(history, "status");
    }

    private static List<String> actors(JsonNode history) {
        return stream(history, "by");
    }

    private static List<String> stream(JsonNode array, String field) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
