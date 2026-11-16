package shipmenttracker;

import java.util.LinkedHashMap;
import java.util.Map;

/** The JSON shapes the API returns, shared by HTTP responses and webhook payloads. */
final class Views {

    private Views() {}

    static Map<String, Object> shipment(Shipment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Long.toString(s.id()));
        m.put("carrierId", s.carrierId());
        m.put("tenantId", s.tenantId());
        m.put("trackingNumber", s.trackingNumber());
        m.put("reference", s.reference());
        m.put("status", s.status().name());
        m.put("pickedUpBy", s.pickedUpBy());
        m.put("updatedAt", s.updatedAt().toString());
        return m;
    }

    static Map<String, Object> event(Shipment.Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", e.status().name());
        m.put("by", e.actorName());
        m.put("note", e.note());
        m.put("at", e.at().toString());
        return m;
    }
}
