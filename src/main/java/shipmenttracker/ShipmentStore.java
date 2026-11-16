package shipmenttracker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Reads and writes shipments and their history.
 *
 * <p>Every read and update takes the caller and filters on their organisation:
 * a carrier sees only the shipments it owns, a tenant only the shipments sent
 * to it (including the orders it placed).
 */
public class ShipmentStore {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String COLUMNS =
            "id, carrier_id, tenant_id, tracking_number, reference, status, picked_up_by, updated_at";

    /** The outcome of a status change. */
    public sealed interface Update {
        record Done(Shipment shipment) implements Update {}

        /** No such shipment that the caller may see. */
        record NotFound() implements Update {}

        /** The caller can't move the shipment from its current status to the requested one. */
        record NotAllowed(ShipmentStatus current, Set<ShipmentStatus> allowed) implements Update {}
    }

    private final DataSource db;
    private final WebhookStore webhooks;

    public ShipmentStore(DataSource db, WebhookStore webhooks) {
        this.db = db;
        this.webhooks = webhooks;
    }

    /** A new shipment, and the id of the order's callback if one was registered with it. */
    public record Created(Shipment shipment, Long callbackId) {}

    /** A carrier creates a shipment to send to a tenant. It starts as CREATED. */
    public Shipment create(Tokens.Principal worker, long tenantId, String trackingNumber, String reference) {
        return insert(worker.orgId(), tenantId, trackingNumber, reference, ShipmentStatus.CREATED, worker,
                null, "tenant not found").shipment();
    }

    /**
     * A tenant orders a shipment from a carrier. It starts as ORDERED, with no
     * tracking number yet. With a {@code callbackUrl}, that URL is told about
     * every change to this shipment, starting with the order itself.
     */
    public Created order(Tokens.Principal tenantUser, long carrierId, String reference, String callbackUrl) {
        return insert(carrierId, tenantUser.orgId(), null, reference, ShipmentStatus.ORDERED, tenantUser,
                callbackUrl, "carrier not found");
    }

    /** Inserts a shipment, its first history step, its callback and its webhook events in one transaction. */
    private Created insert(long carrierId, long tenantId, String trackingNumber, String reference,
                           ShipmentStatus status, Tokens.Principal actor, String callbackUrl,
                           String missingMessage) {
        String insert = "INSERT INTO shipments (carrier_id, tenant_id, tracking_number, reference, status) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                Shipment s;
                try (PreparedStatement ps = c.prepareStatement(insert)) {
                    ps.setLong(1, carrierId);
                    ps.setLong(2, tenantId);
                    setNullableString(ps, 3, trackingNumber);
                    setNullableString(ps, 4, reference);
                    ps.setString(5, status.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        s = row(rs);
                    }
                }
                addEvent(c, s.id(), status, actor.userId(), null);
                Long callbackId = callbackUrl == null ? null : webhooks.createCallback(c, actor, s.id(), callbackUrl);
                webhooks.enqueue(c, s, null, actor.username(), null);
                c.commit();
                return new Created(s, callbackId);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) {
                throw new AccountStore.MissingException(missingMessage);
            }
            throw new StoreException("create shipment failed", e);
        }
    }

    /** Returns the shipment only if the caller's organisation may see it. */
    public Optional<Shipment> get(Tokens.Principal caller, long id) {
        String sql = "SELECT " + COLUMNS + " FROM shipments WHERE id = ? AND " + scope(caller);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, caller.orgId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("get shipment failed", e);
        }
    }

    public List<Shipment> list(Tokens.Principal caller) {
        String sql = "SELECT " + COLUMNS + " FROM shipments WHERE " + scope(caller) + " ORDER BY id";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, caller.orgId());
            try (ResultSet rs = ps.executeQuery()) {
                List<Shipment> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(row(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new StoreException("list shipments failed", e);
        }
    }

    /** The shipment's history, oldest first, or empty if the caller may not see the shipment. */
    public Optional<List<Shipment.Event>> history(Tokens.Principal caller, long id) {
        if (get(caller, id).isEmpty()) {
            return Optional.empty();
        }
        String sql = "SELECT e.status, e.actor_id, u.username, e.note, e.created_at "
                + "FROM shipment_events e LEFT JOIN users u ON u.id = e.actor_id "
                + "WHERE e.shipment_id = ? ORDER BY e.id";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<Shipment.Event> out = new ArrayList<>();
                while (rs.next()) {
                    long actor = rs.getLong("actor_id");
                    Long actorId = rs.wasNull() ? null : actor;
                    out.add(new Shipment.Event(
                            ShipmentStatus.valueOf(rs.getString("status")),
                            actorId, rs.getString("username"), rs.getString("note"),
                            rs.getTimestamp("created_at").toInstant()));
                }
                return Optional.of(out);
            }
        } catch (SQLException e) {
            throw new StoreException("shipment history failed", e);
        }
    }

    /**
     * Moves a shipment to its next status. Carriers make every move except
     * cancelling; a tenant may only cancel its own order while it is ORDERED.
     *
     * <p>The row is locked while the move is checked, so two people changing the
     * same shipment at once can't both succeed from the same starting status.
     *
     * @param trackingNumber set on the shipment when given (used when a carrier accepts an order)
     */
    public Update updateStatus(Tokens.Principal caller, long id, ShipmentStatus to, String note, String trackingNumber) {
        String lock = "SELECT " + COLUMNS + " FROM shipments WHERE id = ? AND " + scope(caller) + " FOR UPDATE";
        String update = "UPDATE shipments SET status = ?, updated_at = now(), "
                + "picked_up_by = CASE WHEN ? THEN ? ELSE picked_up_by END, "
                + "tracking_number = COALESCE(?, tracking_number) "
                + "WHERE id = ? RETURNING " + COLUMNS;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                Shipment current;
                try (PreparedStatement ps = c.prepareStatement(lock)) {
                    ps.setLong(1, id);
                    ps.setLong(2, caller.orgId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return new Update.NotFound();
                        }
                        current = row(rs);
                    }
                }
                Set<ShipmentStatus> allowed = current.status().nextFor(caller.orgType());
                // Shipments from before carriers existed have no carrier, so nobody can move them.
                if (!allowed.contains(to) || current.carrierId() == null) {
                    c.rollback();
                    return new Update.NotAllowed(current.status(), current.carrierId() == null ? Set.of() : allowed);
                }
                Shipment updated;
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    ps.setString(1, to.name());
                    ps.setBoolean(2, to == ShipmentStatus.PICKED_UP);
                    ps.setLong(3, caller.userId());
                    setNullableString(ps, 4, trackingNumber);
                    ps.setLong(5, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        updated = row(rs);
                    }
                }
                addEvent(c, id, to, caller.userId(), note);
                webhooks.enqueue(c, updated, current.status(), caller.username(), note);
                c.commit();
                return new Update.Done(updated);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException("update status failed", e);
        }
    }

    private static void addEvent(Connection c, long shipmentId, ShipmentStatus status, long actorId, String note)
            throws SQLException {
        String sql = "INSERT INTO shipment_events (shipment_id, status, actor_id, note) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, shipmentId);
            ps.setString(2, status.name());
            ps.setLong(3, actorId);
            setNullableString(ps, 4, note);
            ps.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** The WHERE clause limiting rows to what the caller's organisation may see. Takes one parameter: the org id. */
    private static String scope(Tokens.Principal caller) {
        return caller.isCarrier() ? "carrier_id = ?" : "tenant_id = ?";
    }

    private static Shipment row(ResultSet rs) throws SQLException {
        long carrier = rs.getLong("carrier_id");
        Long carrierId = rs.wasNull() ? null : carrier;
        long picker = rs.getLong("picked_up_by");
        Long pickedUpBy = rs.wasNull() ? null : picker;
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Shipment(rs.getLong("id"), carrierId, rs.getLong("tenant_id"),
                rs.getString("tracking_number"), rs.getString("reference"),
                ShipmentStatus.valueOf(rs.getString("status")),
                pickedUpBy, updated.toInstant());
    }
}
