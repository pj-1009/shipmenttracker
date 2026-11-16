package shipmenttracker;

import java.util.EnumSet;
import java.util.Set;

/**
 * The steps a shipment goes through, and which step may follow which.
 *
 * <pre>
 * (tenant orders)                        (carrier creates)
 *    ORDERED ──accept──► CREATED ◄────────────┘
 *     │    │                │
 *  reject cancel            ▼
 *     ▼    ▼             PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
 * REJECTED CANCELLED                                 ↑      │
 *                                                    │      ▼
 *                                               DELIVERY_FAILED → RETURNED
 * </pre>
 *
 * The receiving tenant may only cancel its own order while it is still
 * ORDERED. Every other move is made by the carrier.
 */
public enum ShipmentStatus {
    // Listed in the usual order; stored by name, so the order here can change freely.
    ORDERED,
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNED,
    REJECTED,
    CANCELLED;

    /** The statuses this one may move to next, by anyone. */
    public Set<ShipmentStatus> next() {
        return switch (this) {
            case ORDERED -> EnumSet.of(CREATED, REJECTED, CANCELLED);
            case CREATED -> EnumSet.of(PICKED_UP);
            case PICKED_UP -> EnumSet.of(IN_TRANSIT);
            case IN_TRANSIT -> EnumSet.of(OUT_FOR_DELIVERY);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, DELIVERY_FAILED);
            case DELIVERY_FAILED -> EnumSet.of(OUT_FOR_DELIVERY, RETURNED);
            case REJECTED, CANCELLED, DELIVERED, RETURNED -> EnumSet.noneOf(ShipmentStatus.class);
        };
    }

    /** True for the end states: DELIVERED, RETURNED, REJECTED and CANCELLED. */
    public boolean isFinal() {
        return next().isEmpty();
    }

    public boolean canMoveTo(ShipmentStatus to) {
        return next().contains(to);
    }

    /** The statuses this one may move to next, by a user of the given kind. */
    public Set<ShipmentStatus> nextFor(Tokens.OrgType who) {
        Set<ShipmentStatus> out = EnumSet.noneOf(ShipmentStatus.class);
        for (ShipmentStatus to : next()) {
            boolean tenantMove = to == CANCELLED;
            if ((who == Tokens.OrgType.TENANT) == tenantMove) {
                out.add(to);
            }
        }
        return out;
    }

    /** Parses a status name, or returns null if it isn't one. */
    public static ShipmentStatus parse(String s) {
        try {
            return s == null ? null : valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
