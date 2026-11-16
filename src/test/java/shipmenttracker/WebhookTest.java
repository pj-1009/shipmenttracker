package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tenants and carriers get told about changes to their shipments. */
class WebhookTest {

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
    void tenantLearnsTrackingNumberWhenOrderIsAccepted() throws Exception {
        String secret = register(alice, receiver.url());

        String id = server.placeOrder(alice, dave.orgId(), "PO-1001");
        assertEquals(200, server.accept(dave, id, "FF123456789").status());

        List<WebhookReceiver.Call> calls = receiver.await(2);
        JsonNode ordered = calls.get(0).json();
        JsonNode accepted = calls.get(1).json();

        assertEquals("shipment.status_changed", ordered.get("type").asText());
        assertEquals("ORDERED", ordered.get("status").asText());
        assertTrue(ordered.get("previousStatus").isNull());
        assertEquals("alice", ordered.get("changedBy").asText());

        assertEquals("ORDERED", accepted.get("previousStatus").asText());
        assertEquals("CREATED", accepted.get("status").asText());
        assertEquals("dave", accepted.get("changedBy").asText());
        assertEquals(id, accepted.get("shipment").get("id").asText());
        assertEquals("FF123456789", accepted.get("shipment").get("trackingNumber").asText());
        assertEquals("PO-1001", accepted.get("shipment").get("reference").asText());

        // Each call is signed with this webhook's secret, and names its event.
        for (WebhookReceiver.Call c : calls) {
            assertSigned(c, secret);
            assertEquals(c.json().get("id").asText(), c.eventId());
        }
    }

    @Test
    void everyStepIsSentWithNotes() throws Exception {
        register(alice, receiver.url());
        String id = server.placeOrder(alice, dave.orgId(), "PO-1");
        server.accept(dave, id, "FF1");
        for (String s : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY")) {
            server.setStatus(dave, id, s);
        }
        server.post("/shipments/" + id + "/status", dave.token(), Map.of("status", "DELIVERED", "note", "left at door"));

        // Arrive in the order they happened.
        List<WebhookReceiver.Call> calls = receiver.await(6);
        assertEquals(List.of("ORDERED", "CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"),
                calls.stream().map(c -> field(c, "status")).toList());
        assertEquals("left at door", calls.get(5).json().get("note").asText());
    }

    @Test
    void carrierHearsAboutNewOrdersAndCancellations() throws Exception {
        WebhookReceiver carrierHook = new WebhookReceiver();
        try (carrierHook) {
            register(dave, carrierHook.url());
            String id = server.placeOrder(alice, dave.orgId(), "PO-7");
            server.post("/shipments/" + id + "/cancel", alice.token(), Map.of("reason", "changed my mind"));

            List<WebhookReceiver.Call> calls = carrierHook.await(2);
            assertEquals(List.of("ORDERED", "CANCELLED"), calls.stream().map(c -> field(c, "status")).toList());
        }
    }

    @Test
    void othersHearNothing() throws Exception {
        TestServer.Account bob = server.tenantWithUser("globex", "bob");
        TestServer.Account erin = server.carrierWithWorker("quickship", "erin");
        WebhookReceiver bobHook = new WebhookReceiver();
        WebhookReceiver erinHook = new WebhookReceiver();
        try (bobHook; erinHook) {
            register(bob, bobHook.url());
            register(erin, erinHook.url());
            register(alice, receiver.url());

            String id = server.placeOrder(alice, dave.orgId(), "PO-1");
            server.accept(dave, id, "FF1");

            receiver.await(2); // alice's arrive...
            Thread.sleep(300); // ...give any stray ones time to show up
            assertEquals(0, bobHook.calls().size(), "another tenant");
            assertEquals(0, erinHook.calls().size(), "another carrier");
        }
    }

    @Test
    void retriesUntilTheReceiverAccepts() throws Exception {
        String hook = registerId(alice, receiver.url());
        receiver.answerWith(500, 503); // fails twice, then 200

        server.placeOrder(alice, dave.orgId(), "PO-1");

        List<WebhookReceiver.Call> calls = receiver.await(3);
        assertEquals(1, calls.stream().map(WebhookReceiver.Call::eventId).distinct().count(), "same event each time");
        assertTrue(Duration.between(calls.get(0).at(), calls.get(1).at()).toMillis() >= 80, "waits before retrying");

        JsonNode d = awaitDelivery(hook, "DELIVERED");
        assertEquals(3, d.get("attempts").asInt());
        assertEquals(200, d.get("lastStatusCode").asInt());
    }

    @Test
    void aFailingEventHoldsBackLaterOnesSoOrderIsKept() throws Exception {
        register(alice, receiver.url());
        receiver.answerWith(500, 500); // the first event fails twice

        String id = server.placeOrder(alice, dave.orgId(), "PO-1");
        server.accept(dave, id, "FF1");
        server.setStatus(dave, id, "PICKED_UP");

        // ORDERED is tried 3 times; CREATED and PICKED_UP wait for it, then follow in order.
        List<WebhookReceiver.Call> calls = receiver.await(5);
        assertEquals(List.of("ORDERED", "ORDERED", "ORDERED", "CREATED", "PICKED_UP"),
                calls.stream().map(c -> field(c, "status")).toList());
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        String hook = registerId(alice, receiver.url());
        receiver.answerWith(500, 500, 500, 500, 500, 500); // more failures than allowed tries (4)

        server.placeOrder(alice, dave.orgId(), "PO-1");

        JsonNode d = awaitDelivery(hook, "FAILED");
        assertEquals(TestServer.WEBHOOKS.maxAttempts(), d.get("attempts").asInt());
        assertEquals(500, d.get("lastStatusCode").asInt());
        assertEquals("receiver answered 500", d.get("lastError").asText());
        assertEquals(TestServer.WEBHOOKS.maxAttempts(), receiver.calls().size());
    }

    @Test
    void unreachableReceiverIsRetriedThenFails() throws Exception {
        WebhookReceiver gone = new WebhookReceiver();
        String url = gone.url();
        gone.close(); // nothing listens there any more
        String hook = registerId(alice, url);

        server.placeOrder(alice, dave.orgId(), "PO-1");

        JsonNode d = awaitDelivery(hook, "FAILED");
        assertTrue(d.get("lastStatusCode").isNull());
        assertTrue(d.get("lastError").asText().contains("Connect"), d.get("lastError").asText());
    }

    @Test
    void manageWebhooks() throws Exception {
        TestServer.Response created = server.post("/webhooks", alice.token(), Map.of("url", receiver.url()));
        assertEquals(201, created.status(), created.body().toString());
        String id = created.body().get("id").asText();
        assertTrue(created.body().get("secret").asText().startsWith("whsec_"));

        // The list never shows the secret.
        TestServer.Response list = server.get("/webhooks", alice.token());
        assertEquals(1, list.body().size());
        assertEquals(receiver.url(), list.body().get(0).get("url").asText());
        assertFalse(list.body().get(0).has("secret"));

        // Another org can't see, read or delete it.
        TestServer.Account bob = server.tenantWithUser("globex", "bob");
        assertEquals(0, server.get("/webhooks", bob.token()).body().size());
        assertEquals(404, server.get("/webhooks/" + id + "/deliveries", bob.token()).status());
        assertEquals(404, server.delete("/webhooks/" + id, bob.token()).status());
        assertEquals(404, server.delete("/webhooks/" + id, dave.token()).status());

        // Once deleted, nothing more is sent.
        assertEquals(204, server.delete("/webhooks/" + id, alice.token()).status());
        assertEquals(0, server.get("/webhooks", alice.token()).body().size());
        server.placeOrder(alice, dave.orgId(), "PO-1");
        Thread.sleep(300);
        assertEquals(0, receiver.calls().size());

        assertEquals(401, server.get("/webhooks", null).status());
        assertEquals(400, server.post("/webhooks", alice.token(), Map.of()).status());
        assertEquals(400, server.post("/webhooks", alice.token(), Map.of("url", "ftp://example.com/x")).status());
        assertEquals(400, server.post("/webhooks", alice.token(), Map.of("url", "http://user:pw@127.0.0.1/x")).status());
    }

    @Test
    void limitsWebhooksPerOrg() throws Exception {
        for (int i = 0; i < WebhookStore.MAX_WEBHOOKS_PER_ORG; i++) {
            assertEquals(201, server.post("/webhooks", alice.token(), Map.of("url", receiver.url() + "?n=" + i)).status());
        }
        assertEquals(409, server.post("/webhooks", alice.token(), Map.of("url", receiver.url())).status());
    }

    @Test
    void secureDefaultsRefuseInternalAddresses() throws Exception {
        server.close();
        server = new TestServer(null, WebhookDispatcher.Config.defaults());
        alice = server.tenantWithUser("acme", "alice");

        for (String url : List.of(
                "http://example.com/hook",            // not https
                "https://127.0.0.1/hook",             // loopback
                "https://localhost/hook",
                "https://10.0.0.5/hook",              // private networks
                "https://192.168.1.10/hook",
                "https://169.254.169.254/latest/meta-data", // cloud metadata
                "https://[::1]/hook",
                "https://[fd00::1]/hook",
                "https://100.64.0.1/hook",
                "https://0.0.0.0/hook")) {
            TestServer.Response r = server.post("/webhooks", alice.token(), Map.of("url", url));
            assertEquals(400, r.status(), url);
        }
    }

    @Test
    void addressChecks() throws Exception {
        assertFalse(WebhookUrls.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(WebhookUrls.isPublic(InetAddress.getByName("10.1.2.3")));
        assertFalse(WebhookUrls.isPublic(InetAddress.getByName("172.16.0.1")));
        assertFalse(WebhookUrls.isPublic(InetAddress.getByName("::ffff:10.0.0.1")));
        assertTrue(WebhookUrls.isPublic(InetAddress.getByName("93.184.215.14")));
        assertTrue(WebhookUrls.isPublic(InetAddress.getByName("2606:4700::1111")));
    }

    @Test
    void backoffDoublesUpToTheMax() {
        WebhookDispatcher d = new WebhookDispatcher(null, new WebhookDispatcher.Config(
                false, 8, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ZERO, Duration.ofMinutes(1)));
        assertEquals(Duration.ofSeconds(2), d.backoff(1));
        assertEquals(Duration.ofSeconds(4), d.backoff(2));
        assertEquals(Duration.ofSeconds(16), d.backoff(4));
        assertEquals(Duration.ofSeconds(30), d.backoff(5));
        assertEquals(Duration.ofSeconds(30), d.backoff(40));
    }

    // ---- helpers ------------------------------------------------------------

    /** Registers a webhook and returns its secret. */
    private String register(TestServer.Account who, String url) throws Exception {
        TestServer.Response r = server.post("/webhooks", who.token(), Map.of("url", url));
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("secret").asText();
    }

    /** Registers a webhook and returns its id. */
    private String registerId(TestServer.Account who, String url) throws Exception {
        TestServer.Response r = server.post("/webhooks", who.token(), Map.of("url", url));
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("id").asText();
    }

    /** Waits until the webhook's latest delivery reaches the given status. */
    private JsonNode awaitDelivery(String hook, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode list = server.get("/webhooks/" + hook + "/deliveries", alice.token()).body();
            if (list.size() > 0) {
                last = list.get(0);
                if (status.equals(last.get("status").asText())) {
                    return last;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("delivery never reached " + status + "; last seen: " + last);
    }

    private static void assertSigned(WebhookReceiver.Call c, String secret) {
        assertNotNull(c.signature(), "signature header");
        Matcher m = SIGNATURE.matcher(c.signature());
        assertTrue(m.matches(), c.signature());
        long t = Long.parseLong(m.group(1));
        assertEquals(WebhookDispatcher.sign(secret, t, c.body()), m.group(2), "signature matches body");
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - t) < 60, "timestamp is recent");
        assertFalse(WebhookDispatcher.sign("whsec_wrong", t, c.body()).equals(m.group(2)));
    }

    private static String field(WebhookReceiver.Call c, String name) {
        try {
            return c.json().get(name).asText();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
