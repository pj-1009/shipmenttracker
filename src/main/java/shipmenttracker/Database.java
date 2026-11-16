package shipmenttracker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;

/** The PostgreSQL connection pool and the schema it needs. */
public final class Database implements AutoCloseable {

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS tenants (
            id         BIGSERIAL PRIMARY KEY,
            name       TEXT        NOT NULL UNIQUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        """
        CREATE TABLE IF NOT EXISTS users (
            id            BIGSERIAL PRIMARY KEY,
            tenant_id     BIGINT      NOT NULL REFERENCES tenants(id),
            username      TEXT        NOT NULL UNIQUE,
            password_hash TEXT        NOT NULL,
            created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        """
        CREATE TABLE IF NOT EXISTS shipments (
            id              BIGSERIAL PRIMARY KEY,
            tenant_id       BIGINT      NOT NULL REFERENCES tenants(id),
            tracking_number TEXT        NOT NULL,
            status          TEXT        NOT NULL DEFAULT 'CREATED',
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        // Databases created before tenants existed: add the column. Old rows
        // keep tenant_id NULL, so no tenant can see them.
        "ALTER TABLE shipments ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenants(id)",
        "CREATE INDEX IF NOT EXISTS shipments_tenant_id ON shipments (tenant_id, id)",

        // ---- carriers ----
        """
        CREATE TABLE IF NOT EXISTS carriers (
            id         BIGSERIAL PRIMARY KEY,
            name       TEXT        NOT NULL UNIQUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        // A user belongs to exactly one tenant or exactly one carrier.
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS carrier_id BIGINT REFERENCES carriers(id)",
        "ALTER TABLE users ALTER COLUMN tenant_id DROP NOT NULL",
        """
        DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_one_org') THEN
                ALTER TABLE users ADD CONSTRAINT users_one_org CHECK (num_nonnulls(tenant_id, carrier_id) = 1);
            END IF;
        END $$""",
        // The carrier that owns (and moves) the shipment, and the worker who picked it up.
        // Shipments from before carriers existed keep carrier_id NULL: tenants can
        // still see them, but no carrier can update them.
        "ALTER TABLE shipments ADD COLUMN IF NOT EXISTS carrier_id BIGINT REFERENCES carriers(id)",
        "ALTER TABLE shipments ADD COLUMN IF NOT EXISTS picked_up_by BIGINT REFERENCES users(id)",
        "ALTER TABLE shipments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()",
        "CREATE INDEX IF NOT EXISTS shipments_carrier_id ON shipments (carrier_id, id)",
        """
        CREATE TABLE IF NOT EXISTS shipment_events (
            id          BIGSERIAL PRIMARY KEY,
            shipment_id BIGINT      NOT NULL REFERENCES shipments(id),
            status      TEXT        NOT NULL,
            actor_id    BIGINT      REFERENCES users(id),
            note        TEXT,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        "CREATE INDEX IF NOT EXISTS shipment_events_shipment_id ON shipment_events (shipment_id, id)",

        // ---- orders ----
        // A tenant's order has no tracking number until the carrier accepts it,
        // and may carry the tenant's own reference (for example a PO number).
        "ALTER TABLE shipments ALTER COLUMN tracking_number DROP NOT NULL",
        "ALTER TABLE shipments ADD COLUMN IF NOT EXISTS reference TEXT",

        // ---- webhooks ----
        // A URL a tenant or carrier wants told about changes to its shipments.
        // The secret signs each delivery, so it must be kept to sign with.
        """
        CREATE TABLE IF NOT EXISTS webhooks (
            id         BIGSERIAL PRIMARY KEY,
            tenant_id  BIGINT      REFERENCES tenants(id),
            carrier_id BIGINT      REFERENCES carriers(id),
            url        TEXT        NOT NULL,
            secret     TEXT        NOT NULL,
            created_by BIGINT      REFERENCES users(id),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT webhooks_one_org CHECK (num_nonnulls(tenant_id, carrier_id) = 1)
        )""",
        // The outbox: one row per event per webhook, written in the same
        // transaction as the shipment change, so no event is lost.
        """
        CREATE TABLE IF NOT EXISTS webhook_deliveries (
            id               BIGSERIAL PRIMARY KEY,
            webhook_id       BIGINT      NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
            event_id         UUID        NOT NULL,
            payload          TEXT        NOT NULL,
            status           TEXT        NOT NULL DEFAULT 'PENDING',
            attempts         INT         NOT NULL DEFAULT 0,
            next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
            last_status_code INT,
            last_error       TEXT,
            created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
            delivered_at     TIMESTAMPTZ
        )""",
        "CREATE INDEX IF NOT EXISTS webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING'",
        "CREATE INDEX IF NOT EXISTS webhook_deliveries_webhook ON webhook_deliveries (webhook_id, id)",

        // ---- per-order callbacks ----
        // One secret per tenant signs all its per-order callbacks.
        "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS callback_secret TEXT",
        "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS callback_secret_created_at TIMESTAMPTZ",
        // A webhook with a shipment_id is that order's callback: it gets only that
        // shipment's events and is signed with the tenant's callback secret, so
        // it has no secret of its own.
        "ALTER TABLE webhooks ADD COLUMN IF NOT EXISTS shipment_id BIGINT REFERENCES shipments(id) ON DELETE CASCADE",
        "ALTER TABLE webhooks ALTER COLUMN secret DROP NOT NULL",
        """
        DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'webhooks_secret_or_callback') THEN
                ALTER TABLE webhooks ADD CONSTRAINT webhooks_secret_or_callback
                    CHECK ((shipment_id IS NULL) = (secret IS NOT NULL));
            END IF;
        END $$""",
        "CREATE INDEX IF NOT EXISTS webhooks_shipment_id ON webhooks (shipment_id) WHERE shipment_id IS NOT NULL",
    };

    private final HikariDataSource pool;

    public Database(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setPoolName("shipmenttracker");
        pool = new HikariDataSource(config);
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            for (String sql : SCHEMA) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            pool.close();
            throw new StoreException("could not create schema", e);
        }
    }

    /** Builds a pool from DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD. */
    public static Database fromEnv(Map<String, String> env) {
        return new Database(
                env.getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5433/shipments"),
                env.getOrDefault("DATABASE_USER", "shipments"),
                env.getOrDefault("DATABASE_PASSWORD", "shipments"));
    }

    public DataSource dataSource() {
        return pool;
    }

    /** Removes all data and restarts ids at 1. Used by tests. */
    void deleteAll() {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE webhook_deliveries, webhooks, shipment_events, shipments, users, tenants, carriers "
                    + "RESTART IDENTITY");
        } catch (SQLException e) {
            throw new StoreException("delete failed", e);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
