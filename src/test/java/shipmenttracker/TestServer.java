package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/** Starts the API on a random port against a clean test database, plus HTTP helpers. */
final class TestServer implements AutoCloseable {

    static final String JWT_SECRET = "test-jwt-secret-that-is-at-least-32-bytes-long";
    static final String ADMIN_TOKEN = "test-admin-token-that-is-at-least-32-chars";
    static final ObjectMapper JSON = new ObjectMapper();

    final Database db;
    final Tokens tokens;
    final App app;
    final HttpClient client;
    final String base;

    /**
     * Webhooks for tests: local http receivers allowed, quick retries, and
     * frequent polling so deliveries arrive within a fraction of a second.
     */
    static final WebhookDispatcher.Config WEBHOOKS = new WebhookDispatcher.Config(
            true, 4, Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofMillis(50),
            Duration.ZERO, Duration.ofMillis(50));

    TestServer() throws Exception {
        this(null, WEBHOOKS);
    }

    TestServer(Executor clientExecutor) throws Exception {
        this(clientExecutor, WEBHOOKS);
    }

    TestServer(Executor clientExecutor, WebhookDispatcher.Config webhooks) throws Exception {
        db = Database.fromEnv(System.getenv());
        db.deleteAll();
        tokens = new Tokens(JWT_SECRET, Duration.ofMinutes(5));
        app = new App(0, db, tokens, ADMIN_TOKEN, webhooks);
        app.start();
        HttpClient.Builder b = HttpClient.newBuilder();
        if (clientExecutor != null) {
            b.executor(clientExecutor);
        }
        client = b.build();
        base = "http://localhost:" + app.port();
    }

    @Override
    public void close() {
        app.stop();
        db.close();
    }

    record Response(int status, JsonNode body) {}

    Response get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    Response delete(String path, String token) throws Exception {
        return send(request(path, token).DELETE());
    }

    Response post(String path, String token, Object body) throws Exception {
        String json = JSON.writeValueAsString(body);
        return send(request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** A signed-in user and the tenant or carrier they belong to. */
    record Account(String token, long orgId) {}

    /** Creates a tenant with one user and signs that user in. */
    Account tenantWithUser(String tenant, String username) throws Exception {
        return orgWithUser("/admin/tenants", "tenantId", tenant, username);
    }

    /** Creates a carrier with one worker and signs that worker in. */
    Account carrierWithWorker(String carrier, String username) throws Exception {
        return orgWithUser("/admin/carriers", "carrierId", carrier, username);
    }

    /** Adds another user to an existing tenant ("tenantId") or carrier ("carrierId") and signs them in. */
    Account addUser(String orgField, long orgId, String username) throws Exception {
        Response u = post("/admin/users", ADMIN_TOKEN,
                Map.of(orgField, orgId, "username", username, "password", password(username)));
        assertEquals(201, u.status(), u.body().toString());
        return new Account(login(username, password(username)), orgId);
    }

    /** The test password for a username. */
    static String password(String username) {
        return username + "-password";
    }

    /** A carrier creates a shipment for a tenant; returns its id. */
    String createShipment(Account carrier, long tenantId, String tracking) throws Exception {
        Response r = post("/shipments", carrier.token(), Map.of("trackingNumber", tracking, "tenantId", tenantId));
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("id").asText();
    }

    /** A tenant orders a shipment from a carrier; returns its id. */
    String placeOrder(Account tenant, long carrierId, String reference) throws Exception {
        Response r = post("/shipments", tenant.token(), Map.of("carrierId", carrierId, "reference", reference));
        assertEquals(201, r.status(), r.body().toString());
        return r.body().get("id").asText();
    }

    Response accept(Account worker, String shipmentId, String tracking) throws Exception {
        return post("/shipments/" + shipmentId + "/accept", worker.token(), Map.of("trackingNumber", tracking));
    }

    Response setStatus(Account worker, String shipmentId, String status) throws Exception {
        return post("/shipments/" + shipmentId + "/status", worker.token(), Map.of("status", status));
    }

    private Account orgWithUser(String path, String orgField, String name, String username) throws Exception {
        Response o = post(path, ADMIN_TOKEN, Map.of("name", name));
        assertEquals(201, o.status(), o.body().toString());
        return addUser(orgField, o.body().get("id").asLong(), username);
    }

    String login(String username, String password) throws Exception {
        Response r = post("/login", null, Map.of("username", username, "password", password));
        assertEquals(200, r.status(), r.body().toString());
        return r.body().get("token").asText();
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Response send(HttpRequest.Builder b) throws Exception {
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = r.body().isEmpty() ? JSON.nullNode() : JSON.readTree(r.body());
        return new Response(r.statusCode(), body);
    }
}
