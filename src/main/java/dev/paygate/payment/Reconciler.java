package dev.paygate.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paygate.adyen.AdyenClient;
import dev.paygate.adyen.AdyenResponse;
import dev.paygate.config.Fixes;
import dev.paygate.config.PaygateProperties;
import dev.paygate.stripe.StripeClient;
import dev.paygate.stripe.StripeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Keeps paygate's own decisions and the PSP's later word consistent.
 *
 * <p>When paygate ends a payment without a definitive PSP answer (no answer at all, or its own timeout), it writes a
 * refusal marker. If the PSP authorised the payment anyway, its success webhook says so, and the authorisation must be
 * released: cancel at the PSP, record the payment as COMPLETED, and do not notify the merchant again (it keeps the
 * status it was told). A definitive PSP refusal has no marker, and a later success for it is ignored.
 *
 * <p>The marker and the webhook can arrive in either order. Both paths lock the payment row, so exactly one of them
 * sees the other: the webhook finds the marker, or the marker finds the earlier webhook.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PaymentRepository payments;
    private final RefusalMarkers markers;
    private final MerchantNotifier merchant;
    private final Fixes fixes;
    private final AdyenClient adyen;
    private final StripeClient stripe;
    private final PaygateProperties properties;
    private final ObjectMapper objectMapper;

    public Reconciler(JdbcClient jdbc, TransactionTemplate tx, PaymentRepository payments, RefusalMarkers markers,
                      MerchantNotifier merchant, Fixes fixes, AdyenClient adyen, StripeClient stripe,
                      PaygateProperties properties, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.payments = payments;
        this.markers = markers;
        this.merchant = merchant;
        this.fixes = fixes;
        this.adyen = adyen;
        this.stripe = stripe;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Webhook event codes that mean "authorised": Adyen's AUTHORISATION (success=true), Stripe's PaymentIntent events. */
    static final List<String> SUCCESS_CODES = List.of("AUTHORISATION", "payment_intent.amount_capturable_updated",
            "payment_intent.succeeded");

    /** An authorisation paygate has to release: the payment, its PSP, and the PSP's reference for it. */
    public record Release(UUID paymentId, String psp, String pspReference) {
    }

    /**
     * paygate ends the payment itself, without a definitive PSP answer: status, marker, merchant notified.
     *
     * @param onlyIf when not null, act only while the payment is still in this status
     * @return false when {@code onlyIf} did not match and nothing changed
     */
    public boolean endWithoutAnswer(UUID paymentId, PaymentStatus status, PaymentStatus onlyIf, String reason) {
        return endWithoutAnswerWhen(paymentId, status, onlyIf == null ? current -> true : current -> current == onlyIf, reason);
    }

    /** As above, acting only while {@code when} accepts the payment's current status. */
    public boolean endWithoutAnswerWhen(UUID paymentId, PaymentStatus status, Predicate<PaymentStatus> when, String reason) {
        boolean[] ended = {false};
        Release release = tx.execute(s -> {
            Payment payment = payments.lockById(paymentId);
            if (!when.test(payment.status())) {
                return null;
            }
            ended[0] = true;
            payments.setStatus(paymentId, status, null);
            markers.write(paymentId, reason);
            merchant.notify(paymentId, status, null);
            return null;
        });
        release(release);
        return ended[0];
    }

    /** One NotificationRequestItem whose signature was already checked. */
    public void onWebhookItem(JsonNode item) {
        String eventCode = item.path("eventCode").asText("");
        boolean success = "true".equals(item.path("success").asText());
        String pspReference = item.path("pspReference").asText("");
        String merchantReference = item.path("merchantReference").asText(null);
        UUID paymentId = parseId(merchantReference);

        Release release = tx.execute(s -> {
            Payment payment = paymentId == null ? null : payments.lockByIdIfExists(paymentId).orElse(null);
            boolean fresh = record(payment == null ? null : payment.id(), eventCode, success, pspReference,
                    merchantReference, item);
            if (!fresh) {
                log.info("webhook {} {} for {} already received: duplicate delivery", eventCode, pspReference, merchantReference);
            }
            if (payment == null || !"AUTHORISATION".equals(eventCode) || !success) {
                return null;
            }
            if (markers.unreconciled(payment.id()) > 0) {
                log.warn("success webhook for payment {} that paygate already ended ({}): ignored", payment.id(), payment.status());
                return null;
            }
            if (payment.status() == PaymentStatus.REFUSED || payment.status() == PaymentStatus.FAILED) {
                log.warn("success webhook for payment {} after a definitive {}: ignored", payment.id(), payment.status());
            }
            return null;
        });
        release(release);
    }

    // ------------------------------------------------------------------ inside the transaction (also used by Stripe)

    /**
     * A Stripe success for a locked payment that paygate already ended itself (an unreconciled marker): reconcile when
     * the fix is on, and return the authorisation to release after the commit. Otherwise nothing changes.
     */
    public Release reconcileSuccess(Payment payment, String pspReference) {
        if (markers.unreconciled(payment.id()) == 0) {
            return null;
        }
        log.warn("success for payment {} that paygate already ended ({}): ignored", payment.id(), payment.status());
        return null;
    }

    /** Marks the marker reconciled and the payment COMPLETED. Silent: the merchant keeps the status it heard. */
    public Release claim(Payment payment, String pspReference) {
        if (!markers.reconcile(payment.id())) {
            return null;                       // already reconciled, e.g. by a duplicate delivery
        }
        payments.setStatus(payment.id(), PaymentStatus.COMPLETED, pspReference);
        log.info("payment {}: PSP authorised after paygate ended it; releasing authorisation {}", payment.id(), pspReference);
        return new Release(payment.id(), payment.psp(), pspReference);
    }


    public boolean record(UUID paymentId, String eventCode, boolean success, String pspReference,
                           String merchantReference, JsonNode item) {
        return jdbc.sql("""
                        INSERT INTO psp_event (payment_id, event_code, success, psp_reference, merchant_reference, body)
                        VALUES (:payment, :code, :success, :psp, :ref, CAST(:body AS jsonb))
                        ON CONFLICT (payment_id, psp_reference, event_code, success) DO NOTHING""")
                .param("payment", paymentId).param("code", eventCode).param("success", success)
                .param("psp", pspReference).param("ref", merchantReference).param("body", toJson(item))
                .update() == 1;
    }

    // ------------------------------------------------------------------ after the commit

    public void release(Release release) {
        if (release == null) {
            return;
        }
        if (PaymentRepository.PSP_STRIPE.equals(release.psp())) {
            releaseAtStripe(release);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", properties.adyen().merchantAccount());
        body.put("reference", release.paymentId().toString());
        String status;
        String detail;
        try {
            AdyenResponse answer = adyen.cancel(release.pspReference(), body, release.paymentId() + ":cancel");
            status = answer.accepted() ? "SENT" : "ERROR";
            detail = answer.status() + " " + (answer.body() == null ? "" : answer.body().path("status").asText(""));
        } catch (RestClientException noAnswer) {
            status = "ERROR";
            detail = "no answer: " + noAnswer.getMessage();
        }
        recordCancel(release.paymentId(), release.pspReference(), status, detail);
    }

    /** Stripe answers a cancel with the canceled PaymentIntent at once (no cancellation webhook to wait for). */
    private void releaseAtStripe(Release release) {
        String status;
        String detail;
        try {
            StripeResponse answer = stripe.cancelPaymentIntent(release.pspReference(), release.paymentId() + ":release");
            JsonNode pi = answer.paymentIntent();
            String piStatus = pi == null ? "" : pi.path("status").asText("");
            status = answer.ok() && "canceled".equals(piStatus) ? "CANCELED" : "ERROR";
            detail = answer.status() + " " + piStatus + (answer.errorCode() == null ? "" : " " + answer.errorCode());
        } catch (RestClientException noAnswer) {
            status = "ERROR";
            detail = "no answer: " + noAnswer.getMessage();
        }
        recordCancel(release.paymentId(), release.pspReference(), status, detail);
    }

    /** A cancellation paygate sent to release an authorisation. */
    public void recordCancel(UUID paymentId, String pspReference, String status, String detail) {
        jdbc.sql("""
                        INSERT INTO psp_cancel (payment_id, payment_psp_reference, status, detail)
                        VALUES (:id, :psp, :status, :detail)""")
                .param("id", paymentId).param("psp", pspReference).param("status", status).param("detail", detail)
                .update();
        if ("ERROR".equals(status)) {
            log.error("cancel of {} for payment {} failed: {}", pspReference, paymentId, detail);
        }
    }

    private static UUID parseId(String merchantReference) {
        if (merchantReference == null) {
            return null;
        }
        try {
            return UUID.fromString(merchantReference);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
