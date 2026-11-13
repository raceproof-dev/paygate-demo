package dev.paygate.payment;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {

    private static final String COLUMNS = """
            id, psp, merchant_reference, amount_value, currency, status, three_ds_trans_id, payment_data, psp_reference,
            challenge_started_at""";

    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getString("psp"),
            rs.getString("merchant_reference"),
            rs.getLong("amount_value"),
            rs.getString("currency"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("three_ds_trans_id"),
            rs.getString("payment_data"),
            rs.getString("psp_reference"),
            rs.getObject("challenge_started_at", OffsetDateTime.class));

    public static final String PSP_ADYEN = "adyen";
    public static final String PSP_STRIPE = "stripe";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String merchantReference, long amountValue, String currency) {
        insert(id, PSP_ADYEN, merchantReference, amountValue, currency);
    }

    public void insert(UUID id, String psp, String merchantReference, long amountValue, String currency) {
        jdbc.sql("""
                        INSERT INTO payment (id, psp, merchant_reference, amount_value, currency, status)
                        VALUES (:id, :psp, :ref, :value, :currency, 'CREATED')""")
                .param("id", id).param("psp", psp).param("ref", merchantReference).param("value", amountValue)
                .param("currency", currency)
                .update();
    }

    public Optional<Payment> findByPspReference(String pspReference) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE psp_reference = :psp ORDER BY created_at DESC LIMIT 1")
                .param("psp", pspReference).query(ROW).optional();
    }

    /** A redirect challenge (Stripe) began: the PSP's reference, the status, and when the pending timeout starts. */
    public void startRedirectChallenge(UUID id, String pspReference) {
        jdbc.sql("""
                        UPDATE payment SET status = 'PENDING_CHALLENGE', psp_reference = :psp, challenge_started_at = now(),
                               updated_at = now()
                        WHERE id = :id""")
                .param("psp", pspReference).param("id", id)
                .update();
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE id = :id").param("id", id).query(ROW).optional();
    }

    /** Locks the row until the transaction ends; paths that must see each other's writes take this lock first. */
    public Payment lockById(UUID id) {
        return lockByIdIfExists(id).orElseThrow(() -> new UnknownPaymentException("payment " + id));
    }

    public Optional<Payment> lockByIdIfExists(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE id = :id FOR UPDATE").param("id", id).query(ROW).optional();
    }

    public Optional<Payment> findByThreeDsTransId(String threeDsTransId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE three_ds_trans_id = :t")
                .param("t", threeDsTransId).query(ROW).optional();
    }

    /** The PSP asked for a 3DS step: remember how to continue it. */
    public void startThreeDs(UUID id, PaymentStatus status, String threeDsTransId, String paymentData) {
        jdbc.sql("""
                        UPDATE payment SET status = :status, three_ds_trans_id = :t, payment_data = :pd,
                               challenge_started_at = CASE WHEN :status = 'PENDING_CHALLENGE' THEN now() END,
                               method_started_at = CASE WHEN :status = 'PENDING_METHOD' THEN now() ELSE method_started_at END,
                               updated_at = now()
                        WHERE id = :id""")
                .param("status", status.name()).param("t", threeDsTransId).param("pd", paymentData).param("id", id)
                .update();
    }

    /** Overwrites the status, whatever it was. */
    public void setStatus(UUID id, PaymentStatus status, String pspReference) {
        jdbc.sql("""
                        UPDATE payment SET status = :status, psp_reference = COALESCE(:psp, psp_reference), updated_at = now()
                        WHERE id = :id""")
                .param("status", status.name()).param("psp", pspReference).param("id", id)
                .update();
    }

    /** Sets a final status only while the payment is still open. True if this call ended the payment. */
    public boolean finishIfOpen(UUID id, PaymentStatus status, String pspReference) {
        return jdbc.sql("""
                        UPDATE payment SET status = :status, psp_reference = COALESCE(:psp, psp_reference), updated_at = now()
                        WHERE id = :id AND status NOT IN ('COMPLETED', 'REFUSED', 'FAILED')""")
                .param("status", status.name()).param("psp", pspReference).param("id", id)
                .update() == 1;
    }

    /** Payments still waiting for the shopper's challenge result after the timeout, not yet handled by the watchdog. */
    public List<Payment> findChallengeTimedOut(String psp, OffsetDateTime startedBefore) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM payment
                        WHERE psp = :psp AND status = 'PENDING_CHALLENGE' AND watchdog_fired_at IS NULL
                          AND challenge_started_at < :cutoff
                        ORDER BY challenge_started_at""")
                .param("psp", psp).param("cutoff", startedBefore).query(ROW).list();
    }

    /** Payments whose 3DS method step began before the cutoff and never finished. */
    public List<Payment> findMethodTimedOut(OffsetDateTime startedBefore) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM payment
                        WHERE status = 'PENDING_METHOD' AND method_started_at < :cutoff
                        ORDER BY method_started_at""")
                .param("cutoff", startedBefore).query(ROW).list();
    }

    /** True for exactly one caller: the watchdog acts on each payment once. */
    public boolean claimForWatchdog(UUID id) {
        return jdbc.sql("UPDATE payment SET watchdog_fired_at = now() WHERE id = :id AND watchdog_fired_at IS NULL")
                .param("id", id).update() == 1;
    }
}
