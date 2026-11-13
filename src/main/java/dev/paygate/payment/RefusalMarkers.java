package dev.paygate.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** A marker says: paygate ended this payment without a definitive PSP answer, so a later PSP success must be reconciled. */
@Component
public class RefusalMarkers {

    private final JdbcClient jdbc;

    public RefusalMarkers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void write(UUID paymentId, String reason) {
        jdbc.sql("INSERT INTO refusal_marker (payment_id, reason) VALUES (:id, :reason)")
                .param("id", paymentId).param("reason", reason)
                .update();
    }

    public int unreconciled(UUID paymentId) {
        return jdbc.sql("SELECT count(*) FROM refusal_marker WHERE payment_id = :id AND reconciled_at IS NULL")
                .param("id", paymentId).query(Integer.class).single();
    }

    /** True for exactly one caller: marks the payment's open markers reconciled. */
    public boolean reconcile(UUID paymentId) {
        return jdbc.sql("UPDATE refusal_marker SET reconciled_at = now() WHERE payment_id = :id AND reconciled_at IS NULL")
                .param("id", paymentId).update() > 0;
    }

    public int count(UUID paymentId) {
        return jdbc.sql("SELECT count(*) FROM refusal_marker WHERE payment_id = :id")
                .param("id", paymentId).query(Integer.class).single();
    }
}
