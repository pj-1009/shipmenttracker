package shipmenttracker;

import java.time.Instant;

/**
 * A shipment, owned by a carrier and sent to a tenant.
 *
 * @param carrierId      the carrier that owns and moves it (null for shipments made before carriers existed)
 * @param tenantId       the tenant it is sent to
 * @param trackingNumber the carrier's tracking number; null while it is still an order
 * @param reference      the tenant's own reference for an order (for example a PO number), or null
 * @param pickedUpBy     the carrier worker who picked it up, or null if not picked up yet
 */
public record Shipment(
        long id,
        Long carrierId,
        long tenantId,
        String trackingNumber,
        String reference,
        ShipmentStatus status,
        Long pickedUpBy,
        Instant updatedAt) {

    /** One step in a shipment's history. */
    public record Event(ShipmentStatus status, Long actorId, String actorName, String note, Instant at) {}
}
