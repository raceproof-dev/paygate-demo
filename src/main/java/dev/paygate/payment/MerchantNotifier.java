package dev.paygate.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells the merchant about a status change. paygate stands in for the callback by recording it in
 * merchant_notification, so a test can count what the merchant heard (e.g. exactly one final status).
 */
@Component
public class MerchantNotifier {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotifier.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public MerchantNotifier(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void notify(UUID paymentId, PaymentStatus status, JsonNode detail) {
        jdbc.sql("INSERT INTO merchant_notification (payment_id, status, detail) VALUES (:id, :status, CAST(:detail AS jsonb))")
                .param("id", paymentId).param("status", status.name()).param("detail", toJson(detail))
                .update();
        log.info("merchant notified: payment {} -> {}", paymentId, status);
    }

    /** What the merchant was told about a payment, in order (for the demo page). */
    public List<Map<String, Object>> list(UUID paymentId) {
        return jdbc.sql("SELECT id, status, created_at FROM merchant_notification WHERE payment_id = :id ORDER BY id")
                .param("id", paymentId)
                .query((rs, n) -> Map.<String, Object>of("id", rs.getLong("id"), "status", rs.getString("status"),
                        "at", rs.getObject("created_at", java.time.OffsetDateTime.class).toString()))
                .list();
    }

    /** The detail of the last notification with this status, e.g. the 3DS action that came with PENDING_CHALLENGE. */
    public Optional<JsonNode> lastDetail(UUID paymentId, PaymentStatus status) {
        return jdbc.sql("SELECT detail::text FROM merchant_notification WHERE payment_id = :id AND status = :status "
                        + "AND detail IS NOT NULL ORDER BY id DESC LIMIT 1")
                .param("id", paymentId).param("status", status.name())
                .query(String.class).optional()
                .map(this::fromJson);
    }

    private JsonNode fromJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(JsonNode detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
