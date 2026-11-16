package shipmenttracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Webhook registrations and the delivery outbox.
 *
 * <p>There are two kinds of webhook:
 * <ul>
 *   <li><b>Org webhooks</b> belong to a tenant or carrier and get events for all
 *       its shipments. Each has its own secret.
 *   <li><b>Per-order callbacks</b> are registered with an order, get events for
 *       that one shipment, and are signed with the tenant's shared callback
 *       secret. They're removed once the order is finished and sent.
 * </ul>
 *
 * <p>When a shipment changes, {@link #enqueue} writes one delivery row per
 * matching webhook inside the same transaction as the change.
 * {@link WebhookDispatcher} sends them afterwards.
 */
public class WebhookStore {

    public static final int MAX_WEBHOOKS_PER_ORG = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param shipmentId set for a per-order callback, null for an org webhook */
    public record Webhook(long id, String url, String secret, Long shipmentId, Instant createdAt) {}

    public record Delivery(long id, UUID eventId, String status, int attempts, Integer lastStatusCode,
                           String lastError, Instant createdAt, Instant nextAttemptAt, Instant deliveredAt) {}

    /** A tenant's shared secret for signing its per-order callbacks. */
    public record CallbackSecret(String secret, Instant createdAt) {}

    /** A delivery claimed by the dispatcher, with what it needs to send it. */
    public record Claimed(long id, UUID eventId, String payload, int attempts, String url, String secret) {}

    /** The org already has the maximum number of webhooks. */
    public static final class LimitException extends RuntimeException {
        LimitException(String message) {
            super(message);
        }
    }

    private final DataSource db;

    public WebhookStore(DataSource db) {
        this.db = db;
    }

    // ---- registrations -------------------------------------------------------

    public Webhook create(Tokens.Principal owner, String url) {
        String secret = newSecret();
        // Per-order callbacks don't count towards the limit; they clean themselves up.
        String count = "SELECT count(*) FROM webhooks WHERE shipment_id IS NULL AND " + scope(owner);
        String insert = "INSERT INTO webhooks (tenant_id, carrier_id, url, secret, created_by) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING id, url, secret, created_at";
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialise registrations per org so the limit can't be raced past.
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                    ps.setInt(1, owner.isCarrier() ? 2 : 1);
                    ps.setInt(2, (int) owner.orgId());
                    ps.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(count)) {
                    ps.setLong(1, owner.orgId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) >= MAX_WEBHOOKS_PER_ORG) {
                            c.rollback();
                            throw new LimitException("at most " + MAX_WEBHOOKS_PER_ORG + " webhooks");
                        }
                    }
                }
                Webhook w;
                try (PreparedStatement ps = c.prepareStatement(insert)) {
                    setOrg(ps, 1, owner);
                    ps.setString(3, url);
                    ps.setString(4, secret);
                    ps.setLong(5, owner.userId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        w = new Webhook(rs.getLong("id"), rs.getString("url"), rs.getString("secret"), null,
                                rs.getTimestamp("created_at").toInstant());
                    }
                }
                c.commit();
                return w;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("create webhook failed", e);
        }
    }

    /** The caller's org's webhooks, including per-order callbacks. Secrets are left out. */
    public List<Webhook> list(Tokens.Principal owner) {
        String sql = "SELECT id, url, shipment_id, created_at FROM webhooks WHERE " + scope(owner) + " ORDER BY id";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, owner.orgId());
            try (ResultSet rs = ps.executeQuery()) {
                List<Webhook> out = new ArrayList<>();
                while (rs.next()) {
                    long shipment = rs.getLong("shipment_id");
                    out.add(new Webhook(rs.getLong("id"), rs.getString("url"), null,
                            rs.wasNull() ? null : shipment, rs.getTimestamp("created_at").toInstant()));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new StoreException("list webhooks failed", e);
        }
    }

    /** Deletes the webhook (and its pending deliveries) if it belongs to the caller's org. */
    public boolean delete(Tokens.Principal owner, long id) {
        String sql = "DELETE FROM webhooks WHERE id = ? AND " + scope(owner);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, owner.orgId());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StoreException("delete webhook failed", e);
        }
    }

    /** The latest deliveries of one of the caller's webhooks, newest first; empty if it isn't theirs. */
    public Optional<List<Delivery>> deliveries(Tokens.Principal owner, long webhookId) {
        String own = "SELECT 1 FROM webhooks WHERE id = ? AND " + scope(owner);
        String sql = "SELECT id, event_id, status, attempts, last_status_code, last_error, created_at, "
                + "next_attempt_at, delivered_at FROM webhook_deliveries WHERE webhook_id = ? ORDER BY id DESC LIMIT 50";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(own)) {
                ps.setLong(1, webhookId);
                ps.setLong(2, owner.orgId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, webhookId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Delivery> out = new ArrayList<>();
                    while (rs.next()) {
                        int code = rs.getInt("last_status_code");
                        Integer lastCode = rs.wasNull() ? null : code;
                        out.add(new Delivery(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                                rs.getString("status"), rs.getInt("attempts"), lastCode, rs.getString("last_error"),
                                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("next_attempt_at")),
                                instant(rs.getTimestamp("delivered_at"))));
                    }
                    return Optional.of(out);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("list deliveries failed", e);
        }
    }

    // ---- per-order callbacks -------------------------------------------------

    /**
     * Makes a new callback secret for the tenant, replacing any old one. Callbacks
     * still waiting to be sent are signed with the new secret from now on.
     */
    public CallbackSecret rotateCallbackSecret(long tenantId) {
        String secret = newSecret();
        String sql = "UPDATE tenants SET callback_secret = ?, callback_secret_created_at = now() WHERE id = ? "
                + "RETURNING callback_secret_created_at";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, secret);
            ps.setLong(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AccountStore.MissingException("tenant not found");
                }
                return new CallbackSecret(secret, rs.getTimestamp(1).toInstant());
            }
        } catch (SQLException e) {
            throw new StoreException("rotate callback secret failed", e);
        }
    }

    public boolean hasCallbackSecret(long tenantId) {
        String sql = "SELECT callback_secret IS NOT NULL FROM tenants WHERE id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new StoreException("find callback secret failed", e);
        }
    }

    /** Adds an order's callback. Called inside the transaction that creates the order. */
    long createCallback(Connection c, Tokens.Principal tenantUser, long shipmentId, String url) throws SQLException {
        String sql = "INSERT INTO webhooks (tenant_id, shipment_id, url, created_by) VALUES (?, ?, ?, ?) RETURNING id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantUser.orgId());
            ps.setLong(2, shipmentId);
            ps.setString(3, url);
            ps.setLong(4, tenantUser.userId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Removes callbacks whose order finished more than {@code retention} ago and
     * has nothing left to send. Their delivery records go with them.
     */
    int deleteFinishedCallbacks(Duration retention) {
        String finals = java.util.Arrays.stream(ShipmentStatus.values())
                .filter(ShipmentStatus::isFinal)
                .map(st -> "'" + st.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = "DELETE FROM webhooks w USING shipments s "
                + "WHERE w.shipment_id = s.id AND s.status IN (" + finals + ") "
                + "AND s.updated_at < now() - make_interval(secs => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM webhook_deliveries d WHERE d.webhook_id = w.id AND d.status = 'PENDING')";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, retention.toMillis() / 1000.0);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("clean up callbacks failed", e);
        }
    }

    // ---- outbox --------------------------------------------------------------

    /**
     * Queues a "shipment.status_changed" event for every org webhook of the
     * shipment's tenant and carrier, and for the shipment's own callback if it
     * has one. Must be called inside the transaction that made the change, so
     * the event is saved if and only if the change is.
     */
    void enqueue(Connection c, Shipment s, ShipmentStatus previous, String changedBy, String note)
            throws SQLException {
        Map<String, Object> event = new LinkedHashMap<>();
        UUID eventId = UUID.randomUUID();
        event.put("id", eventId.toString());
        event.put("type", "shipment.status_changed");
        event.put("occurredAt", s.updatedAt().toString());
        event.put("previousStatus", previous == null ? null : previous.name());
        event.put("status", s.status().name());
        event.put("changedBy", changedBy);
        event.put("note", note);
        event.put("shipment", Views.shipment(s));
        String payload;
        try {
            payload = JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String sql = "INSERT INTO webhook_deliveries (webhook_id, event_id, payload) "
                + "SELECT id, ?, ? FROM webhooks "
                + "WHERE (shipment_id IS NULL AND (tenant_id = ? OR carrier_id = ?)) OR shipment_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, eventId);
            ps.setString(2, payload);
            ps.setLong(3, s.tenantId());
            if (s.carrierId() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, s.carrierId());
            }
            ps.setLong(5, s.id());
            ps.executeUpdate();
        }
    }

    /**
     * Claims up to {@code limit} due deliveries for this dispatcher. Claiming
     * counts an attempt and pushes the next try out by {@code lease}, so if this
     * pod dies mid-send another pod retries later. SKIP LOCKED lets several pods
     * claim at once without taking the same rows.
     *
     * <p>Each webhook gets its events in order: a delivery is only due once
     * every earlier delivery to the same webhook is DELIVERED or FAILED. A
     * claimed delivery stays PENDING until it's sent, so it holds back the
     * next one, on every pod.
     *
     * <p>A per-order callback is signed with its tenant's current callback secret.
     */
    List<Claimed> claim(int limit, Duration lease) {
        String sql = """
                UPDATE webhook_deliveries d
                   SET attempts = d.attempts + 1,
                       next_attempt_at = now() + make_interval(secs => ?)
                  FROM webhooks w
                  LEFT JOIN tenants t ON t.id = w.tenant_id
                 WHERE w.id = d.webhook_id
                   AND d.id IN (SELECT p.id FROM webhook_deliveries p
                                 WHERE p.status = 'PENDING' AND p.next_attempt_at <= now()
                                   AND NOT EXISTS (SELECT 1 FROM webhook_deliveries earlier
                                                    WHERE earlier.webhook_id = p.webhook_id
                                                      AND earlier.status = 'PENDING'
                                                      AND earlier.id < p.id)
                                 ORDER BY p.id LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING d.id, d.event_id, d.payload, d.attempts, w.url,
                          COALESCE(w.secret, t.callback_secret) AS secret""";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, lease.toMillis() / 1000.0);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Claimed> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Claimed(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                            rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"), rs.getString("secret")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new StoreException("claim deliveries failed", e);
        }
    }

    void markDelivered(long id, int statusCode) {
        String sql = "UPDATE webhook_deliveries SET status = 'DELIVERED', delivered_at = now(), "
                + "last_status_code = ?, last_error = NULL WHERE id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, statusCode);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("update delivery failed", e);
        }
    }

    /** Records a failed try and schedules the next one after {@code retryIn}. */
    void markRetry(long id, Integer statusCode, String error, Duration retryIn) {
        String sql = "UPDATE webhook_deliveries SET next_attempt_at = now() + make_interval(secs => ?), "
                + "last_status_code = ?, last_error = ? WHERE id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, retryIn.toMillis() / 1000.0);
            setFailure(ps, 2, statusCode, error);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("update delivery failed", e);
        }
    }

    /** Records the last failed try and gives up on the delivery. */
    void markFailed(long id, Integer statusCode, String error) {
        String sql = "UPDATE webhook_deliveries SET status = 'FAILED', last_status_code = ?, last_error = ? WHERE id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            setFailure(ps, 1, statusCode, error);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("update delivery failed", e);
        }
    }

    private static void setFailure(PreparedStatement ps, int first, Integer statusCode, String error)
            throws SQLException {
        if (statusCode == null) {
            ps.setNull(first, Types.INTEGER);
        } else {
            ps.setInt(first, statusCode);
        }
        if (error == null) {
            ps.setNull(first + 1, Types.VARCHAR);
        } else {
            ps.setString(first + 1, error.length() > 500 ? error.substring(0, 500) : error);
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static String scope(Tokens.Principal owner) {
        return owner.isCarrier() ? "carrier_id = ?" : "tenant_id = ?";
    }

    private static void setOrg(PreparedStatement ps, int first, Tokens.Principal owner) throws SQLException {
        if (owner.isCarrier()) {
            ps.setNull(first, Types.BIGINT);
            ps.setLong(first + 1, owner.orgId());
        } else {
            ps.setLong(first, owner.orgId());
            ps.setNull(first + 1, Types.BIGINT);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String newSecret() {
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
