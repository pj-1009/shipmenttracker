package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The basic flow: a carrier creates a shipment for a tenant, both can read it. */
class AppTest {

    private TestServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void healthzIsOpen() throws Exception {
        assertEquals(200, server.get("/healthz", null).status());
    }

    @Test
    void carrierCreatesShipmentForTenant() throws Exception {
        TestServer.Account acme = server.tenantWithUser("acme", "alice");
        TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");

        TestServer.Response created = server.post("/shipments", fast.token(),
                Map.of("trackingNumber", "1Z999AA10123456784", "tenantId", acme.orgId()));
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("CREATED", created.body().get("status").asText());
        assertEquals(fast.orgId(), created.body().get("carrierId").asLong());
        assertEquals(acme.orgId(), created.body().get("tenantId").asLong());
        String id = created.body().get("id").asText();

        // Both the carrier and the receiving tenant can read it.
        assertEquals(200, server.get("/shipments/" + id, fast.token()).status());
        TestServer.Response seen = server.get("/shipments/" + id, acme.token());
        assertEquals(200, seen.status());
        assertEquals("1Z999AA10123456784", seen.body().get("trackingNumber").asText());

        assertEquals(404, server.get("/shipments/999999", acme.token()).status());
        assertEquals(404, server.get("/shipments/not-a-number", acme.token()).status());
    }

    @Test
    void rejectsBadShipmentBody() throws Exception {
        TestServer.Account acme = server.tenantWithUser("acme", "alice");
        TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");
        assertEquals(400, server.post("/shipments", fast.token(), Map.of("tenantId", acme.orgId())).status());
        assertEquals(400, server.post("/shipments", fast.token(), Map.of("trackingNumber", "X")).status());
        assertEquals(404, server.post("/shipments", fast.token(),
                Map.of("trackingNumber", "X", "tenantId", 999)).status());
    }
}
