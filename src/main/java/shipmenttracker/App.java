package shipmenttracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API entry point, built on the JDK's bundled HTTP server.
 *
 * <ul>
 *   <li>{@code /healthz} — open to everyone.
 *   <li>{@code /login} — swaps a username and password for a signed token.
 *   <li>{@code /admin/*} — needs the admin token; creates tenants, carriers and users.
 *   <li>{@code /carriers} — needs a user token; lists carriers to order from.
 *   <li>{@code /webhooks*} — needs a user token; registers URLs to be told
 *       about every change to the org's shipments.
 *   <li>{@code /shipments*} — needs a user token. Tenant users order shipments
 *       from any carrier and follow them; carrier workers accept or reject
 *       orders, create shipments, and move them through the delivery steps.
 * </ul>
 */
public final class App {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MIN_ADMIN_TOKEN_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 500;

    private final HttpServer server;
    // One virtual thread per request, so a slow request never blocks the others.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ShipmentStore shipments;
    private final AccountStore accounts;
    private final WebhookStore webhooks;
    private final WebhookDispatcher dispatcher;
    private final WebhookDispatcher.Config webhookConfig;
    private final Tokens tokens;
    private final byte[] adminToken;

    /** An app with the safe webhook defaults (https to public addresses only). */
    public App(int port, Database db, Tokens tokens, String adminToken) throws IOException {
        this(port, db, tokens, adminToken, WebhookDispatcher.Config.defaults());
    }

    public App(int port, Database db, Tokens tokens, String adminToken, WebhookDispatcher.Config webhookConfig)
            throws IOException {
        if (adminToken == null || adminToken.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("admin token must be at least " + MIN_ADMIN_TOKEN_LENGTH + " characters");
        }
        this.webhooks = new WebhookStore(db.dataSource());
        this.shipments = new ShipmentStore(db.dataSource(), webhooks);
        this.accounts = new AccountStore(db.dataSource());
        this.webhookConfig = webhookConfig;
        this.dispatcher = new WebhookDispatcher(webhooks, webhookConfig);
        this.tokens = tokens;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/healthz", safe(ex -> respond(ex, 200, Map.of("status", "ok"))));
        server.createContext("/login", safe(this::handleLogin));
        server.createContext("/admin/", safe(this::handleAdmin));
        server.createContext("/carriers", safe(this::handleCarriers));
        server.createContext("/shipments", safe(this::handleShipments));
        server.createContext("/webhooks", safe(this::handleWebhooks));
    }

    public void start() {
        server.start();
        dispatcher.start();
    }

    public void stop() {
        dispatcher.close();
        server.stop(0);
        executor.shutdown();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---- /login -------------------------------------------------------------

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/login")) {
            notFound(ex);
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            methodNotAllowed(ex);
            return;
        }
        JsonNode body = readJson(ex);
        String username = text(body, "username");
        String password = text(body, "password");
        if (username == null || password == null) {
            error(ex, 400, "username and password required");
            return;
        }
        Optional<AccountStore.User> user = accounts.findUser(username);
        boolean ok;
        if (user.isPresent()) {
            ok = Passwords.verify(password, user.get().passwordHash());
        } else {
            Passwords.verifyDummy(password); // same cost, so timing doesn't reveal which usernames exist
            ok = false;
        }
        if (!ok) {
            // One message for both cases, so callers can't probe for usernames.
            error(ex, 401, "invalid username or password");
            return;
        }
        AccountStore.User u = user.get();
        String token = tokens.issue(new Tokens.Principal(u.id(), u.orgType(), u.orgId(), u.username()));
        respond(ex, 200, Map.of(
                "token", token,
                "tokenType", "Bearer",
                "expiresIn", tokens.ttl().toSeconds(),
                "orgType", u.orgType().name(),
                "orgId", u.orgId()));
    }

    // ---- /admin/* -----------------------------------------------------------

    private void handleAdmin(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) {
            unauthorized(ex);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!ex.getRequestMethod().equals("POST")) {
            methodNotAllowed(ex);
            return;
        }
        switch (path) {
            case "/admin/tenants" -> createOrg(ex, Tokens.OrgType.TENANT);
            case "/admin/carriers" -> createOrg(ex, Tokens.OrgType.CARRIER);
            case "/admin/users" -> createUser(ex);
            default -> notFound(ex);
        }
    }

    private void createOrg(HttpExchange ex, Tokens.OrgType type) throws IOException {
        String name = text(readJson(ex), "name");
        if (name == null || name.isBlank()) {
            error(ex, 400, "name required");
            return;
        }
        try {
            AccountStore.Org o = type == Tokens.OrgType.TENANT
                    ? accounts.createTenant(name.strip())
                    : accounts.createCarrier(name.strip());
            respond(ex, 201, Map.of("id", o.id(), "name", o.name()));
        } catch (AccountStore.DuplicateException e) {
            error(ex, 409, e.getMessage());
        }
    }

    /** Body: {"tenantId": n} for a tenant user, or {"carrierId": n} for a carrier worker; plus username and password. */
    private void createUser(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        JsonNode tenantId = body.get("tenantId");
        JsonNode carrierId = body.get("carrierId");
        String username = text(body, "username");
        String password = text(body, "password");
        boolean oneOrg = (tenantId == null) != (carrierId == null);
        JsonNode orgId = tenantId != null ? tenantId : carrierId;
        if (!oneOrg || !orgId.canConvertToLong() || username == null || username.isBlank() || password == null) {
            error(ex, 400, "exactly one of tenantId or carrierId, plus username and password, required");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            error(ex, 400, "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
            return;
        }
        Tokens.OrgType type = tenantId != null ? Tokens.OrgType.TENANT : Tokens.OrgType.CARRIER;
        try {
            AccountStore.User u = accounts.createUser(type, orgId.asLong(), username.strip(), Passwords.hash(password));
            respond(ex, 201, Map.of(
                    "id", u.id(), "orgType", u.orgType().name(), "orgId", u.orgId(), "username", u.username()));
        } catch (AccountStore.DuplicateException e) {
            error(ex, 409, e.getMessage());
        } catch (AccountStore.MissingException e) {
            error(ex, 404, e.getMessage());
        }
    }

    private boolean isAdmin(HttpExchange ex) {
        return bearer(ex)
                .map(t -> MessageDigest.isEqual(adminToken, t.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    // ---- /carriers ----------------------------------------------------------

    /** GET /carriers: any signed-in user can list carriers, so tenants can choose whom to order from. */
    private void handleCarriers(HttpExchange ex) throws IOException {
        if (bearer(ex).flatMap(tokens::verify).isEmpty()) {
            unauthorized(ex);
            return;
        }
        if (!ex.getRequestURI().getPath().equals("/carriers")) {
            notFound(ex);
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            methodNotAllowed(ex);
            return;
        }
        respond(ex, 200, accounts.listCarriers().stream()
                .map(c -> Map.of("id", c.id(), "name", c.name()))
                .toList());
    }

    // ---- /shipments ---------------------------------------------------------

    /**
     * <pre>
     * GET  /shipments               list what the caller's org may see
     * POST /shipments               carrier: create {"trackingNumber", "tenantId", "reference"?}
     *                               tenant:  order  {"carrierId", "reference"?}
     * GET  /shipments/{id}          one shipment
     * POST /shipments/{id}/accept   carrier: accept an order {"trackingNumber", "note"?}
     * POST /shipments/{id}/reject   carrier: reject an order {"reason"}
     * POST /shipments/{id}/cancel   tenant:  cancel its order {"reason"?}
     * POST /shipments/{id}/status   carrier: next step {"status", "note"?}
     * GET  /shipments/{id}/history  every step so far
     * </pre>
     */
    private void handleShipments(HttpExchange ex) throws IOException {
        Optional<Tokens.Principal> maybeCaller = bearer(ex).flatMap(tokens::verify);
        if (maybeCaller.isEmpty()) {
            unauthorized(ex);
            return;
        }
        Tokens.Principal caller = maybeCaller.get();
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        // "/shipments" → [], "/shipments/7" → ["7"], "/shipments/7/status" → ["7", "status"]
        if (!path.equals("/shipments") && !path.startsWith("/shipments/")) {
            notFound(ex);
            return;
        }
        String rest = path.substring("/shipments".length()).replaceAll("^/+|/+$", "");
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");

        if (parts.length == 0) {
            switch (method) {
                case "GET" -> respond(ex, 200, shipments.list(caller).stream().map(Views::shipment).toList());
                case "POST" -> {
                    if (caller.isCarrier()) {
                        createShipment(ex, caller);
                    } else {
                        placeOrder(ex, caller);
                    }
                }
                default -> methodNotAllowed(ex);
            }
            return;
        }

        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            notFound(ex);
            return;
        }

        if (parts.length == 1) {
            if (!method.equals("GET")) {
                methodNotAllowed(ex);
                return;
            }
            // A shipment the caller may not see gets 404, not 403, so its existence isn't revealed.
            Optional<Shipment> s = shipments.get(caller, id);
            if (s.isPresent()) {
                respond(ex, 200, Views.shipment(s.get()));
            } else {
                notFound(ex);
            }
            return;
        }

        if (parts.length != 2) {
            notFound(ex);
            return;
        }
        String action = parts[1];
        if (action.equals("history")) {
            if (!method.equals("GET")) {
                methodNotAllowed(ex);
                return;
            }
            Optional<List<Shipment.Event>> events = shipments.history(caller, id);
            if (events.isPresent()) {
                respond(ex, 200, events.get().stream().map(Views::event).toList());
            } else {
                notFound(ex);
            }
            return;
        }
        if (!List.of("status", "accept", "reject", "cancel").contains(action)) {
            notFound(ex);
            return;
        }
        if (!method.equals("POST")) {
            methodNotAllowed(ex);
            return;
        }
        switch (action) {
            case "status" -> updateStatus(ex, caller, id);
            case "accept" -> acceptOrder(ex, caller, id);
            case "reject" -> rejectOrder(ex, caller, id);
            case "cancel" -> cancelOrder(ex, caller, id);
            default -> notFound(ex);
        }
    }

    /** A carrier worker creates a shipment for a tenant. */
    private void createShipment(HttpExchange ex, Tokens.Principal caller) throws IOException {
        JsonNode body = readJson(ex);
        String tracking = text(body, "trackingNumber");
        JsonNode tenantId = body.get("tenantId");
        if (tracking == null || tracking.isBlank() || tenantId == null || !tenantId.canConvertToLong()) {
            error(ex, 400, "trackingNumber and tenantId required");
            return;
        }
        String reference = optionalText(body, "reference");
        try {
            Shipment s = shipments.create(caller, tenantId.asLong(), tracking.strip(), reference);
            respond(ex, 201, Views.shipment(s));
        } catch (AccountStore.MissingException e) {
            error(ex, 404, e.getMessage());
        }
    }

    /**
     * A tenant user orders a shipment from any carrier. It waits as ORDERED until
     * the carrier accepts. An optional {@code callbackUrl} is told about every
     * change to this shipment, signed with the tenant's callback secret.
     */
    private void placeOrder(HttpExchange ex, Tokens.Principal caller) throws IOException {
        JsonNode body = readJson(ex);
        JsonNode carrierId = body.get("carrierId");
        if (carrierId == null || !carrierId.canConvertToLong()) {
            error(ex, 400, "carrierId required");
            return;
        }
        String reference = optionalText(body, "reference");
        String callbackUrl = null;
        if (body.hasNonNull("callbackUrl")) {
            try {
                callbackUrl = WebhookUrls.check(text(body, "callbackUrl"), webhookConfig.allowPrivate()).toString();
            } catch (WebhookUrls.InvalidUrl e) {
                error(ex, 400, "callbackUrl: " + e.getMessage());
                return;
            }
            if (!webhooks.hasCallbackSecret(caller.orgId())) {
                error(ex, 409, "create your callback signing secret first: POST /webhooks/callback-secret");
                return;
            }
        }
        try {
            ShipmentStore.Created created = shipments.order(caller, carrierId.asLong(), reference, callbackUrl);
            Map<String, Object> m = Views.shipment(created.shipment());
            if (created.callbackId() != null) {
                m.put("callback", Map.of("webhookId", created.callbackId(), "url", callbackUrl));
            }
            respond(ex, 201, m);
        } catch (AccountStore.MissingException e) {
            error(ex, 404, e.getMessage());
        }
    }

    private void acceptOrder(HttpExchange ex, Tokens.Principal caller, long id) throws IOException {
        if (!caller.isCarrier()) {
            forbiddenOrNotFound(ex, caller, id, "only the carrier can accept an order");
            return;
        }
        JsonNode body = readJson(ex);
        String tracking = text(body, "trackingNumber");
        if (tracking == null || tracking.isBlank()) {
            error(ex, 400, "trackingNumber required to accept an order");
            return;
        }
        String note = optionalText(body, "note");
        move(ex, caller, id, ShipmentStatus.CREATED, note, tracking.strip());
    }

    private void rejectOrder(HttpExchange ex, Tokens.Principal caller, long id) throws IOException {
        if (!caller.isCarrier()) {
            forbiddenOrNotFound(ex, caller, id, "only the carrier can reject an order");
            return;
        }
        JsonNode body = readJson(ex);
        String reason = optionalText(body, "reason");
        if (reason == null) {
            error(ex, 400, "reason required to reject an order");
            return;
        }
        move(ex, caller, id, ShipmentStatus.REJECTED, reason, null);
    }

    private void cancelOrder(HttpExchange ex, Tokens.Principal caller, long id) throws IOException {
        if (caller.isCarrier()) {
            forbiddenOrNotFound(ex, caller, id, "only the tenant that placed the order can cancel it; carriers reject");
            return;
        }
        String reason = optionalText(readJson(ex), "reason");
        move(ex, caller, id, ShipmentStatus.CANCELLED, reason, null);
    }

    /** Carrier workers move an accepted shipment to its next delivery step. */
    private void updateStatus(HttpExchange ex, Tokens.Principal caller, long id) throws IOException {
        if (!caller.isCarrier()) {
            forbiddenOrNotFound(ex, caller, id, "only the carrier can change a shipment's status");
            return;
        }
        JsonNode body = readJson(ex);
        ShipmentStatus to = ShipmentStatus.parse(text(body, "status"));
        if (to == null) {
            error(ex, 400, "status must be one of " + List.of(ShipmentStatus.values()));
            return;
        }
        if (to == ShipmentStatus.CREATED || to == ShipmentStatus.REJECTED || to == ShipmentStatus.CANCELLED) {
            error(ex, 400, "use /accept, /reject or /cancel for orders");
            return;
        }
        String note = optionalText(body, "note");
        move(ex, caller, id, to, note, null);
    }

    private void move(HttpExchange ex, Tokens.Principal caller, long id, ShipmentStatus to,
                      String note, String trackingNumber) throws IOException {
        switch (shipments.updateStatus(caller, id, to, note, trackingNumber)) {
            case ShipmentStore.Update.Done d -> respond(ex, 200, Views.shipment(d.shipment()));
            case ShipmentStore.Update.NotFound n -> notFound(ex);
            case ShipmentStore.Update.NotAllowed na -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("error", "cannot move from " + na.current() + " to " + to);
                m.put("current", na.current().name());
                m.put("allowed", na.allowed().stream().map(Enum::name).toList());
                respond(ex, 409, m);
            }
        }
    }

    /** 403 if the caller can see the shipment but may not do this; 404 if they can't see it at all. */
    private void forbiddenOrNotFound(HttpExchange ex, Tokens.Principal caller, long id, String message)
            throws IOException {
        if (shipments.get(caller, id).isEmpty()) {
            notFound(ex);
        } else {
            error(ex, 403, message);
        }
    }

    /**
     * Reads an optional text field (a note, reason or reference): null if missing
     * or blank, otherwise the stripped text. Not text, or too long, is a 400.
     */
    private static String optionalText(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new BadRequest(field + " must be text");
        }
        String s = n.asText().strip();
        if (s.length() > MAX_NOTE_LENGTH) {
            throw new BadRequest(field + " must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        return s.isEmpty() ? null : s;
    }

    // ---- /webhooks ----------------------------------------------------------

    /**
     * <pre>
     * POST   /webhooks                   register {"url"}; the reply holds the signing secret (shown once)
     * GET    /webhooks                   list your org's webhooks
     * DELETE /webhooks/{id}              remove one (pending deliveries are dropped)
     * GET    /webhooks/{id}/deliveries   the latest 50 deliveries and how they went
     * POST   /webhooks/callback-secret   tenant: make (or replace) the secret that signs per-order callbacks
     * </pre>
     * Tenant users and carrier workers both manage their own org's webhooks.
     */
    private void handleWebhooks(HttpExchange ex) throws IOException {
        Optional<Tokens.Principal> maybeCaller = bearer(ex).flatMap(tokens::verify);
        if (maybeCaller.isEmpty()) {
            unauthorized(ex);
            return;
        }
        Tokens.Principal caller = maybeCaller.get();
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (!path.equals("/webhooks") && !path.startsWith("/webhooks/")) {
            notFound(ex);
            return;
        }
        String rest = path.substring("/webhooks".length()).replaceAll("^/+|/+$", "");
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");

        if (parts.length == 0) {
            switch (method) {
                case "GET" -> respond(ex, 200, webhooks.list(caller).stream().map(App::webhookJson).toList());
                case "POST" -> createWebhook(ex, caller);
                default -> methodNotAllowed(ex);
            }
            return;
        }
        if (parts.length == 1 && parts[0].equals("callback-secret")) {
            if (!method.equals("POST")) {
                methodNotAllowed(ex);
                return;
            }
            if (caller.isCarrier()) {
                error(ex, 403, "callback secrets are for tenants' per-order callbacks");
                return;
            }
            WebhookStore.CallbackSecret cs = webhooks.rotateCallbackSecret(caller.orgId());
            respond(ex, 201, Map.of("secret", cs.secret(), "createdAt", cs.createdAt().toString()));
            return;
        }
        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            notFound(ex);
            return;
        }
        if (parts.length == 1) {
            if (!method.equals("DELETE")) {
                methodNotAllowed(ex);
                return;
            }
            if (webhooks.delete(caller, id)) {
                ex.sendResponseHeaders(204, -1);
            } else {
                notFound(ex);
            }
            return;
        }
        if (parts.length == 2 && parts[1].equals("deliveries")) {
            if (!method.equals("GET")) {
                methodNotAllowed(ex);
                return;
            }
            Optional<List<WebhookStore.Delivery>> ds = webhooks.deliveries(caller, id);
            if (ds.isPresent()) {
                respond(ex, 200, ds.get().stream().map(App::deliveryJson).toList());
            } else {
                notFound(ex);
            }
            return;
        }
        notFound(ex);
    }

    private void createWebhook(HttpExchange ex, Tokens.Principal caller) throws IOException {
        String url = text(readJson(ex), "url");
        java.net.URI uri;
        try {
            uri = WebhookUrls.check(url, webhookConfig.allowPrivate());
        } catch (WebhookUrls.InvalidUrl e) {
            error(ex, 400, e.getMessage());
            return;
        }
        try {
            WebhookStore.Webhook w = webhooks.create(caller, uri.toString());
            Map<String, Object> m = webhookJson(w);
            m.put("secret", w.secret());
            respond(ex, 201, m);
        } catch (WebhookStore.LimitException e) {
            error(ex, 409, e.getMessage());
        }
    }

    private static Map<String, Object> webhookJson(WebhookStore.Webhook w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("url", w.url());
        m.put("shipmentId", w.shipmentId() == null ? null : Long.toString(w.shipmentId()));
        m.put("createdAt", w.createdAt().toString());
        return m;
    }

    private static Map<String, Object> deliveryJson(WebhookStore.Delivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("eventId", d.eventId().toString());
        m.put("status", d.status());
        m.put("attempts", d.attempts());
        m.put("lastStatusCode", d.lastStatusCode());
        m.put("lastError", d.lastError());
        m.put("createdAt", d.createdAt().toString());
        m.put("nextAttemptAt", "PENDING".equals(d.status()) ? d.nextAttemptAt().toString() : null);
        m.put("deliveredAt", d.deliveredAt() == null ? null : d.deliveredAt().toString());
        return m;
    }

    // ---- helpers ------------------------------------------------------------

    /** Thrown for request bodies that are too big or not valid JSON. */
    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private static HttpHandler safe(Handler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (BadRequest e) {
                error(ex, 400, e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("request failed: " + e);
                error(ex, 500, "internal error");
            } finally {
                ex.close();
            }
        };
    }

    private static Optional<String> bearer(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new BadRequest("request body too large");
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new BadRequest("request body must be a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new BadRequest("request body must be valid JSON");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static void unauthorized(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        error(ex, 401, "unauthorized");
    }

    private static void notFound(HttpExchange ex) throws IOException {
        error(ex, 404, "not found");
    }

    private static void methodNotAllowed(HttpExchange ex) throws IOException {
        error(ex, 405, "method not allowed");
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        respond(ex, status, Map.of("error", message));
    }

    private static void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- main ---------------------------------------------------------------

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        Duration ttl = Duration.ofMinutes(Long.parseLong(env.getOrDefault("TOKEN_TTL_MINUTES", "60")));
        Tokens tokens = new Tokens(required(env, "JWT_SECRET"), ttl);
        String adminToken = required(env, "ADMIN_TOKEN");

        WebhookDispatcher.Config defaults = WebhookDispatcher.Config.defaults();
        WebhookDispatcher.Config webhookConfig = new WebhookDispatcher.Config(
                Boolean.parseBoolean(env.getOrDefault("WEBHOOK_ALLOW_PRIVATE", "false")),
                Integer.parseInt(env.getOrDefault("WEBHOOK_MAX_ATTEMPTS", Integer.toString(defaults.maxAttempts()))),
                defaults.retryBase(), defaults.retryMax(), defaults.pollInterval(),
                defaults.callbackRetention(), defaults.cleanupInterval());
        if (webhookConfig.allowPrivate()) {
            System.err.println("WARNING: WEBHOOK_ALLOW_PRIVATE=true; webhooks may call internal addresses. Dev only.");
        }

        Database db = connectWithRetry(env);
        App app = new App(port, db, tokens, adminToken, webhookConfig);
        app.start();
        System.out.println("shipmenttracker listening on :" + app.port());
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }

    /** The database may start after the app (e.g. in Kubernetes), so keep trying for a while. */
    private static Database connectWithRetry(Map<String, String> env) throws InterruptedException {
        int attempts = 30;
        for (int i = 1; ; i++) {
            try {
                return Database.fromEnv(env);
            } catch (RuntimeException e) {
                if (i == attempts) {
                    throw e;
                }
                System.err.println("database not ready (attempt " + i + "/" + attempts + "): " + e.getMessage());
                Thread.sleep(2000);
            }
        }
    }
}
