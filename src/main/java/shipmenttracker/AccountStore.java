package shipmenttracker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import javax.sql.DataSource;

/** Reads and writes tenants, carriers and their users. */
public class AccountStore {

    /** PostgreSQL error codes. */
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** A tenant or a carrier. */
    public record Org(long id, String name) {}

    public record User(long id, Tokens.OrgType orgType, long orgId, String username, String passwordHash) {}

    /** The name is already taken. */
    public static final class DuplicateException extends RuntimeException {
        DuplicateException(String message) {
            super(message);
        }
    }

    /** A referenced row (e.g. the tenant for a new user) does not exist. */
    public static final class MissingException extends RuntimeException {
        MissingException(String message) {
            super(message);
        }
    }

    private static final String USER_COLUMNS = "id, tenant_id, carrier_id, username, password_hash";

    private final DataSource db;

    public AccountStore(DataSource db) {
        this.db = db;
    }

    public Org createTenant(String name) {
        return createOrg("tenants", "tenant", name);
    }

    public Org createCarrier(String name) {
        return createOrg("carriers", "carrier", name);
    }

    private Org createOrg(String table, String what, String name) {
        String sql = "INSERT INTO " + table + " (name) VALUES (?) RETURNING id, name";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Org(rs.getLong("id"), rs.getString("name"));
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new DuplicateException(what + " already exists");
            }
            throw new StoreException("create " + what + " failed", e);
        }
    }

    /** Every carrier, so tenants can choose whom to order from. */
    public java.util.List<Org> listCarriers() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, name FROM carriers ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            java.util.List<Org> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(new Org(rs.getLong("id"), rs.getString("name")));
            }
            return out;
        } catch (SQLException e) {
            throw new StoreException("list carriers failed", e);
        }
    }

    public boolean tenantExists(long id) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tenants WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StoreException("find tenant failed", e);
        }
    }

    public User createUser(Tokens.OrgType orgType, long orgId, String username, String passwordHash) {
        String sql = "INSERT INTO users (tenant_id, carrier_id, username, password_hash) VALUES (?, ?, ?, ?) "
                + "RETURNING " + USER_COLUMNS;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (orgType == Tokens.OrgType.TENANT) {
                ps.setLong(1, orgId);
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setNull(1, Types.BIGINT);
                ps.setLong(2, orgId);
            }
            ps.setString(3, username);
            ps.setString(4, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return user(rs);
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new DuplicateException("username already exists");
            }
            if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) {
                throw new MissingException(orgType.name().toLowerCase() + " not found");
            }
            throw new StoreException("create user failed", e);
        }
    }

    public Optional<User> findUser(String username) {
        String sql = "SELECT " + USER_COLUMNS + " FROM users WHERE username = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(user(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("find user failed", e);
        }
    }

    private static User user(ResultSet rs) throws SQLException {
        long tenant = rs.getLong("tenant_id");
        boolean isTenant = !rs.wasNull();
        long carrier = rs.getLong("carrier_id");
        return new User(rs.getLong("id"),
                isTenant ? Tokens.OrgType.TENANT : Tokens.OrgType.CARRIER,
                isTenant ? tenant : carrier,
                rs.getString("username"), rs.getString("password_hash"));
    }
}
