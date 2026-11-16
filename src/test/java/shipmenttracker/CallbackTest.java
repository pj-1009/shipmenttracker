package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A callback URL registered with an order, signed with the tenant's shared callback secret. */
class CallbackTest {

    private static final Pattern SIGNATURE = Pattern.compile("t=(\\d+),v1=([0-9a-f]{64})");

    private TestServer server;
    private WebhookReceiver receiver;
    private TestServer.Account alice; // tenant acme
    private TestServer.Account dave;  // carrier fastfreight

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        receiver = new WebhookReceiver();
        alice = server.tenantWithUser("acme", "alice");
        dave = server.carrierWithWorker("fastfreight", "dave");
    }

    @AfterEach
    void stop() {
        server.close();
        receiver.close();
    }

    @Test
    void callbackRegisteredWithTheOrderGetsItsEvents() throws Exception {
        String secret = callbackSecret(alice);

        TestServer.Response placed = order(alice, receiver.url() + "?po=1001", "PO-1001");
        assertEquals(201, placed.status(), placed.body().toString());
        assertEquals("ORDERED", placed.body().get("status").asText());
        JsonNode callback = placed.body().get("callback");
        assertEquals(receiver.url() + "?po=1001", callback.get("url").asText());
        assertTrue(callback.get("webhookId").canConvertToLong());
        assertFalse(callback.has("secret"), "per-order callbacks use the tenant's shared secret");
        String id = placed.body().get("id").asText();

        server.accept(dave, id, "FF123456789");

        List<WebhookReceiver.Call> calls = receiver.await(2);
        assertEquals("ORDERED", field(calls.get(0), "status"));
        assertEquals("CREATED", field(calls.get(1), "status"));
        assertEquals("FF123456789", calls.get(1).json().get("shipment").get("trackingNumber").asText());
        for (WebhookReceiver.Call c : calls) {
            assertSignedWith(c, secret);
        }
    }

    @Test
    void oneSecretSignsEveryOrdersCallback() throws Exception {
        String secret = callbackSecret(alice);
        String first = order(alice, receiver.url() + "?n=1", "PO-1").body().get("id").asText();
        String second = order(alice, receiver.url() + "?n=2", "PO-2").body().get("id").asText();

        List<WebhookReceiver.Call> calls = receiver.await(2);
        assertEquals(List.of(first, second).stream().sorted().toList(),
                calls.stream().map(c -> shipmentId(c)).sorted().toList());
        for (WebhookReceiver.Call c : calls) {
            assertSignedWith(c, secret);
        }
    }

    @Test
    void eachCallbackOnlyHearsAboutItsOwnOrder() throws Exception {
        callbackSecret(alice);
        WebhookReceiver other = new WebhookReceiver();
        try (other) {
            String mine = order(alice, receiver.url(), "PO-1").body().get("id").asText();
            String theirs = order(alice, other.url(), "PO-2").body().get("id").asText();
            server.accept(dave, theirs, "FF2");

            assertEquals(List.of(mine), receiver.await(1).stream().map(CallbackTest::shipmentId).toList());
            other.await(2);
            Thread.sleep(300);
            assertTrue(receiver.calls().stream().allMatch(c -> shipmentId(c).equals(mine)));
            assertTrue(other.calls().stream().allMatch(c -> shipmentId(c).equals(theirs)));
        }
    }

    @Test
    void tenantWebhookStillGetsEverythingAlongsideTheCallback() throws Exception {
        callbackSecret(alice);
        WebhookReceiver orgHook = new WebhookReceiver();
        try (orgHook) {
            TestServer.Response r = server.post("/webhooks", alice.token(), Map.of("url", orgHook.url()));
            String orgSecret = r.body().get("secret").asText();

            order(alice, receiver.url(), "PO-1");
            order(alice, null, "PO-2");

            receiver.await(1);
            List<WebhookReceiver.Call> all = orgHook.await(2);
            for (WebhookReceiver.Call c : all) {
                assertSignedWith(c, orgSecret); // the org webhook keeps its own secret
            }
        }
    }

    @Test
    void carrierCannotSeeTheTenantsCallbackUrl() throws Exception {
        callbackSecret(alice);
        String id = order(alice, receiver.url(), "PO-1").body().get("id").asText();
        JsonNode seen = server.get("/shipments/" + id, dave.token()).body();
        assertFalse(seen.has("callback"));
        assertFalse(seen.toString().contains(receiver.url()));
        assertEquals(0, server.get("/webhooks", dave.token()).body().size());
    }

    @Test
    void needsASecretFirst() throws Exception {
        TestServer.Response r = order(alice, receiver.url(), "PO-1");
        assertEquals(409, r.status());
        assertTrue(r.body().get("error").asText().contains("/webhooks/callback-secret"));
        assertEquals(0, server.get("/shipments", alice.token()).body().size(), "no order was placed");
    }

    @Test
    void badCallbackUrlRejectsTheWholeOrder() throws Exception {
        callbackSecret(alice);
        assertEquals(400, order(alice, "ftp://example.com/x", "PO-1").status());
        assertEquals(400, order(alice, "not a url", "PO-1").status());
        assertEquals(0, server.get("/shipments", alice.token()).body().size(), "no order was placed");
    }

    @Test
    void secureDefaultsApplyToCallbacksToo() throws Exception {
        server.close();
        server = new TestServer(null, WebhookDispatcher.Config.defaults());
        alice = server.tenantWithUser("acme", "alice");
        dave = server.carrierWithWorker("fastfreight", "dave");
        callbackSecret(alice);
        for (String url : List.of("http://example.com/x", "https://127.0.0.1/x", "https://10.0.0.1/x",
                "https://169.254.169.254/latest/meta-data")) {
            assertEquals(400, order(alice, url, "PO-1").status(), url);
        }
    }

    @Test
    void rotatingTheSecretSignsLaterCallbacksWithTheNewOne() throws Exception {
        String oldSecret = callbackSecret(alice);
        String id = order(alice, receiver.url(), "PO-1").body().get("id").asText();
        assertSignedWith(receiver.await(1).get(0), oldSecret);

        String newSecret = callbackSecret(alice);
        assertFalse(newSecret.equals(oldSecret));
        server.accept(dave, id, "FF1");

        WebhookReceiver.Call accepted = receiver.await(2).get(1);
        assertSignedWith(accepted, newSecret);
        assertFalse(signedWith(accepted, oldSecret));
    }

    @Test
    void onlyTenantsHaveCallbackSecrets() throws Exception {
        assertEquals(403, server.post("/webhooks/callback-secret", dave.token(), Map.of()).status());
        assertEquals(401, server.post("/webhooks/callback-secret", null, Map.of()).status());
    }

    @Test
    void callbackShowsInTheWebhookListAndCanBeRemoved() throws Exception {
        callbackSecret(alice);
        TestServer.Response placed = order(alice, receiver.url(), "PO-1");
        String shipment = placed.body().get("id").asText();
        String hook = placed.body().get("callback").get("webhookId").asText();
        receiver.await(1);

        JsonNode list = server.get("/webhooks", alice.token()).body();
        assertEquals(1, list.size());
        assertEquals(shipment, list.get(0).get("shipmentId").asText());
        assertEquals(1, server.get("/webhooks/" + hook + "/deliveries", alice.token()).body().size());

        assertEquals(204, server.delete("/webhooks/" + hook, alice.token()).status());
        server.accept(dave, shipment, "FF1");
        Thread.sleep(300);
        assertEquals(1, receiver.calls().size(), "nothing sent after removal");
    }

    @Test
    void callbacksDontCountTowardsTheWebhookLimit() throws Exception {
        callbackSecret(alice);
        for (int i = 0; i < WebhookStore.MAX_WEBHOOKS_PER_ORG; i++) {
            assertEquals(201, server.post("/webhooks", alice.token(), Map.of("url", receiver.url() + "?n=" + i)).status());
        }
        assertEquals(201, order(alice, receiver.url() + "?order", "PO-1").status());
    }

    @Test
    void finishedOrdersCallbackIsRemovedAfterItsLastEvent() throws Exception {
        callbackSecret(alice); // tests keep finished callbacks for 0s, so cleanup is quick
        String id = order(alice, receiver.url(), "PO-1").body().get("id").asText();
        server.accept(dave, id, "FF1");
        for (String s : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")) {
            server.setStatus(dave, id, s);
        }

        // Every event, including the final DELIVERED, arrives before the callback goes.
        List<WebhookReceiver.Call> calls = receiver.await(6);
        assertEquals("DELIVERED", field(calls.get(5), "status"));
        long deadline = System.currentTimeMillis() + 5_000;
        while (server.get("/webhooks", alice.token()).body().size() > 0) {
            assertTrue(System.currentTimeMillis() < deadline, "callback was never cleaned up");
            Thread.sleep(25);
        }
    }

    @Test
    void openOrdersKeepTheirCallback() throws Exception {
        callbackSecret(alice);
        order(alice, receiver.url(), "PO-1");
        receiver.await(1);
        Thread.sleep(300); // several cleanup rounds
        assertEquals(1, server.get("/webhooks", alice.token()).body().size());
    }

    // ---- helpers ------------------------------------------------------------

    /** Makes (or replaces) the tenant's callback secret and returns it. */
    private String callbackSecret(TestServer.Account tenant) throws Exception {
        TestServer.Response r = server.post("/webhooks/callback-secret", tenant.token(), Map.of());
        assertEquals(201, r.status(), r.body().toString());
        assertTrue(r.body().get("secret").asText().startsWith("whsec_"));
        return r.body().get("secret").asText();
    }

    private TestServer.Response order(TestServer.Account tenant, String callbackUrl, String reference) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("carrierId", dave.orgId());
        body.put("reference", reference);
        if (callbackUrl != null) {
            body.put("callbackUrl", callbackUrl);
        }
        return server.post("/shipments", tenant.token(), body);
    }

    private static void assertSignedWith(WebhookReceiver.Call c, String secret) {
        assertTrue(signedWith(c, secret), "signature should match secret");
    }

    private static boolean signedWith(WebhookReceiver.Call c, String secret) {
        Matcher m = SIGNATURE.matcher(c.signature());
        assertTrue(m.matches(), c.signature());
        return WebhookDispatcher.sign(secret, Long.parseLong(m.group(1)), c.body()).equals(m.group(2));
    }

    private static String field(WebhookReceiver.Call c, String name) {
        try {
            return c.json().get(name).asText();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String shipmentId(WebhookReceiver.Call c) {
        try {
            return c.json().get("shipment").get("id").asText();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
