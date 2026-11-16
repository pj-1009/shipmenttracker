package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Login, tokens, the admin endpoints, and who can see which shipments. */
class AuthTest {

    private TestServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    // ---- who can see what ---------------------------------------------------

    @Test
    void tenantsOnlySeeShipmentsSentToThem() throws Exception {
        TestServer.Account acme = server.tenantWithUser("acme", "alice");
        TestServer.Account globex = server.tenantWithUser("globex", "bob");
        TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");

        String toAcme = server.createShipment(fast, acme.orgId(), "ACME-1");
        server.createShipment(fast, globex.orgId(), "GLOBEX-1");

        // Bob can't read Acme's shipment, and gets 404 rather than 403.
        assertEquals(404, server.get("/shipments/" + toAcme, globex.token()).status());
        assertEquals(404, server.get("/shipments/" + toAcme + "/history", globex.token()).status());
        assertEquals(200, server.get("/shipments/" + toAcme, acme.token()).status());

        TestServer.Response acmeList = server.get("/shipments", acme.token());
        assertEquals(1, acmeList.body().size());
        assertEquals("ACME-1", acmeList.body().get(0).get("trackingNumber").asText());
        TestServer.Response globexList = server.get("/shipments", globex.token());
        assertEquals(1, globexList.body().size());
        assertEquals("GLOBEX-1", globexList.body().get(0).get("trackingNumber").asText());

        // The carrier sees both, since it owns both.
        assertEquals(2, server.get("/shipments", fast.token()).body().size());
    }

    @Test
    void carriersOnlySeeTheirOwnShipments() throws Exception {
        TestServer.Account acme = server.tenantWithUser("acme", "alice");
        TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");
        TestServer.Account quick = server.carrierWithWorker("quickship", "erin");

        String fastShipment = server.createShipment(fast, acme.orgId(), "FAST-1");
        server.createShipment(quick, acme.orgId(), "QUICK-1");

        assertEquals(404, server.get("/shipments/" + fastShipment, quick.token()).status());
        assertEquals(404, server.setStatus(quick, fastShipment, "PICKED_UP").status());
        assertEquals(1, server.get("/shipments", quick.token()).body().size());
        assertEquals(1, server.get("/shipments", fast.token()).body().size());

        // The tenant gets shipments from both carriers.
        assertEquals(2, server.get("/shipments", acme.token()).body().size());
    }

    @Test
    void usersInTheSameTenantShareShipments() throws Exception {
        TestServer.Account alice = server.tenantWithUser("acme", "alice");
        TestServer.Account carol = server.addUser("tenantId", alice.orgId(), "carol");
        TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");

        String id = server.createShipment(fast, alice.orgId(), "ACME-1");
        assertEquals(200, server.get("/shipments/" + id, carol.token()).status());
    }

    @Test
    void tenantsOrderInsteadOfCreating() throws Exception {
        TestServer.Account acme = server.tenantWithUser("acme", "alice");
        // A tenant can't make a ready shipment for itself; it must order one from a carrier.
        TestServer.Response r = server.post("/shipments", acme.token(),
                Map.of("trackingNumber", "X", "tenantId", acme.orgId()));
        assertEquals(400, r.status());
        assertEquals(0, server.get("/shipments", acme.token()).body().size());
    }

    // ---- authentication on /shipments ---------------------------------------

    @Test
    void shipmentsNeedAToken() throws Exception {
        assertEquals(401, server.get("/shipments", null).status());
        assertEquals(401, server.post("/shipments", null, Map.of("trackingNumber", "X")).status());
        assertEquals(401, server.get("/shipments/1", null).status());
        assertEquals(401, server.post("/shipments/1/status", null, Map.of("status", "PICKED_UP")).status());
        assertEquals(401, server.get("/shipments", "not-a-jwt").status());
    }

    @Test
    void adminTokenIsNotAUserToken() throws Exception {
        assertEquals(401, server.get("/shipments", TestServer.ADMIN_TOKEN).status());
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() throws Exception {
        String forged = JWT.create().withIssuer("shipmenttracker").withSubject("1")
                .withClaim("orgType", "CARRIER").withClaim("orgId", 1L).withClaim("username", "mallory")
                .withExpiresAt(Instant.now().plusSeconds(600))
                .sign(Algorithm.HMAC256("some-other-secret-that-is-32-bytes-long!!"));
        assertEquals(401, server.get("/shipments", forged).status());
    }

    @Test
    void rejectsUnsignedToken() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = b64.encodeToString(("{\"iss\":\"shipmenttracker\",\"sub\":\"1\",\"orgType\":\"CARRIER\","
                + "\"orgId\":1,\"username\":\"mallory\",\"exp\":9999999999}").getBytes());
        assertEquals(401, server.get("/shipments", header + "." + payload + ".").status());
    }

    @Test
    void rejectsTamperedClaims() throws Exception {
        TestServer.Account alice = server.tenantWithUser("acme", "alice");
        String[] parts = alice.token().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String changed = payload.replace("\"TENANT\"", "\"CARRIER\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes()) + "." + parts[2];
        assertEquals(401, server.get("/shipments", tampered).status());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        Tokens oldTokens = new Tokens(TestServer.JWT_SECRET, Duration.ofMinutes(5), past);
        String expired = oldTokens.issue(new Tokens.Principal(1, Tokens.OrgType.TENANT, 1, "alice"));
        assertEquals(401, server.get("/shipments", expired).status());
    }

    @Test
    void rejectsTokenWithoutOrgType() throws Exception {
        // Tokens issued before carriers existed carried only a "tenant" claim.
        String old = JWT.create().withIssuer("shipmenttracker").withSubject("1")
                .withClaim("tenant", 1L).withClaim("username", "alice")
                .withExpiresAt(Instant.now().plusSeconds(600))
                .sign(Algorithm.HMAC256(TestServer.JWT_SECRET));
        assertEquals(401, server.get("/shipments", old).status());
    }

    // ---- login --------------------------------------------------------------

    @Test
    void loginRejectsWrongPasswordAndUnknownUserTheSameWay() throws Exception {
        server.tenantWithUser("acme", "alice");
        TestServer.Response wrong = server.post("/login", null, Map.of("username", "alice", "password", "nope-nope"));
        TestServer.Response unknown = server.post("/login", null, Map.of("username", "nobody", "password", "nope-nope"));
        assertEquals(401, wrong.status());
        assertEquals(401, unknown.status());
        assertEquals(wrong.body(), unknown.body());
    }

    @Test
    void loginSaysWhichKindOfUser() throws Exception {
        server.carrierWithWorker("fastfreight", "dave");
        TestServer.Response r = server.post("/login", null,
                Map.of("username", "dave", "password", TestServer.password("dave")));
        assertEquals("CARRIER", r.body().get("orgType").asText());
    }

    @Test
    void loginRejectsBadBody() throws Exception {
        assertEquals(400, server.post("/login", null, Map.of("username", "alice")).status());
    }

    @Test
    void passwordsAreStoredHashed() {
        String hash = Passwords.hash("alice-password");
        assertFalse(hash.contains("alice-password"));
        assertTrue(Passwords.verify("alice-password", hash));
        assertFalse(Passwords.verify("wrong-password", hash));
        assertFalse(hash.equals(Passwords.hash("alice-password")), "salt makes each hash different");
    }

    // ---- admin endpoints ----------------------------------------------------

    @Test
    void adminEndpointsNeedTheAdminToken() throws Exception {
        assertEquals(401, server.post("/admin/tenants", null, Map.of("name", "acme")).status());
        assertEquals(401, server.post("/admin/carriers", "wrong-admin-token-wrong-admin-token", Map.of("name", "x")).status());

        TestServer.Account alice = server.tenantWithUser("acme", "alice");
        TestServer.Account dave = server.carrierWithWorker("fastfreight", "dave");
        for (TestServer.Account a : new TestServer.Account[] {alice, dave}) {
            assertEquals(401, server.post("/admin/tenants", a.token(), Map.of("name", "evil")).status());
            assertEquals(401, server.post("/admin/carriers", a.token(), Map.of("name", "evil")).status());
            assertEquals(401, server.post("/admin/users", a.token(),
                    Map.of("tenantId", 1, "username", "eve", "password", "eve-password")).status());
        }
    }

    @Test
    void adminValidatesInput() throws Exception {
        String admin = TestServer.ADMIN_TOKEN;
        assertEquals(201, server.post("/admin/tenants", admin, Map.of("name", "acme")).status());
        assertEquals(409, server.post("/admin/tenants", admin, Map.of("name", "acme")).status());
        assertEquals(201, server.post("/admin/carriers", admin, Map.of("name", "fastfreight")).status());
        assertEquals(409, server.post("/admin/carriers", admin, Map.of("name", "fastfreight")).status());

        assertEquals(404, server.post("/admin/users", admin,
                Map.of("tenantId", 999, "username", "x", "password", "long-enough")).status());
        assertEquals(404, server.post("/admin/users", admin,
                Map.of("carrierId", 999, "username", "x", "password", "long-enough")).status());
        assertEquals(400, server.post("/admin/users", admin,
                Map.of("tenantId", 1, "username", "x", "password", "short")).status());
        assertEquals(400, server.post("/admin/users", admin,
                Map.of("tenantId", 1, "carrierId", 1, "username", "x", "password", "long-enough")).status());
        assertEquals(400, server.post("/admin/users", admin,
                Map.of("username", "x", "password", "long-enough")).status());
        assertEquals(201, server.post("/admin/users", admin,
                Map.of("tenantId", 1, "username", "alice", "password", "alice-password")).status());
        assertEquals(409, server.post("/admin/users", admin,
                Map.of("carrierId", 1, "username", "alice", "password", "alice-password")).status());
    }
}
