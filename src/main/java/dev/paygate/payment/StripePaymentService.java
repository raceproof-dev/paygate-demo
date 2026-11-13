package dev.paygate.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paygate.config.Fixes;
import dev.paygate.config.PaygateProperties;
import dev.paygate.stripe.StripeClient;
import dev.paygate.stripe.StripeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stripe with its built-in 3DS: one PaymentIntent per payment, created and confirmed at once, with manual capture
 * (an authorisation shows as requires_capture).
 * <pre>
 *   POST /v1/payment_intents (confirm, request_three_d_secure=challenge) -> requires_action: the shopper's browser
 *       opens next_action.redirect_to_url; or a final answer (requires_capture, a 402 decline)
 *   then, in any order and possibly more than once:
 *       the browser comes back to return_url      -> paygate retrieves the PaymentIntent
 *       webhooks (payment_intent.*, charge.*)     -> each carries a snapshot of the object at its moment
 *   or nothing arrives: at the pending timeout paygate cancels the PaymentIntent
 * </pre>
 * Measured in a Stripe sandbox (see the lab's psps/stripe/core.py): the same idempotency key replays the first answer,
 * errors too; retried webhooks come hours later, out of order, with old snapshots; after a cancel only the latest charge
 * tells a released authorisation (succeeded) from a decline (failed).
 */
@Service
public class StripePaymentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);
    private static final String DEFAULT_PAYMENT_METHOD = "pm_card_threeDSecure2Required";

    private final StripeClient stripe;
    private final PaymentRepository payments;
    private final MerchantNotifier merchant;
    private final Reconciler reconciler;
    private final RefusalMarkers markers;
    private final Fixes fixes;
    private final PaygateProperties properties;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public StripePaymentService(StripeClient stripe, PaymentRepository payments, MerchantNotifier merchant,
                                Reconciler reconciler, RefusalMarkers markers, Fixes fixes, PaygateProperties properties,
                                TransactionTemplate tx, ObjectMapper objectMapper) {
        this.stripe = stripe;
        this.payments = payments;
        this.merchant = merchant;
        this.reconciler = reconciler;
        this.markers = markers;
        this.fixes = fixes;
        this.properties = properties;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ start

    public PaymentView start(StartPaymentRequest request) {
        UUID id = request.transactionId() != null ? request.transactionId() : UUID.randomUUID();
        payments.insert(id, PaymentRepository.PSP_STRIPE, request.merchantReference(), request.amount().value(),
                request.amount().currency());

        StripeResponse answer = create(id, request);
        if (answer == null || answer.undecided()) {
            // No definitive answer (the same key replays a 500, so a retry cannot tell): Stripe may have authorised.
            // FAILED plus a marker, so a later success webhook (metadata payment_id) can be reconciled.
            reconciler.endWithoutAnswerWhen(id, PaymentStatus.FAILED, current -> !current.isFinal(),
                    "no definitive answer to the PaymentIntent create: " + (answer == null ? "no answer" : answer.status()));
            return view(id, null);
        }
        JsonNode pi = answer.paymentIntent();
        if (answer.status() == 402 && pi != null) {
            finishInTx(id, PaymentStatus.REFUSED, pi.path("id").asText(null), "create: " + answer.errorCode());
            return view(id, null);
        }
        if (!answer.ok() || pi == null) {
            // Stripe rejected the request (e.g. 400 invalid parameters): nothing was created, nothing to reconcile.
            log.warn("payment {}: Stripe rejected the create: {} {}", id, answer.status(), answer.errorCode());
            finishInTx(id, PaymentStatus.FAILED, null, "create rejected");
            return view(id, null);
        }
        if ("requires_action".equals(pi.path("status").asText())) {
            return beginChallenge(id, pi);
        }
        applyInTx(id, pi, "create");
        return view(id, null);
    }

    /** Create + confirm, retried once with the same key when there is no definitive answer. */
    private StripeResponse create(UUID id, StartPaymentRequest request) {
        Map<String, Object> params = createParams(id, request);
        String key = id + ":create";
        for (int attempt = 1; ; attempt++) {
            try {
                StripeResponse answer = stripe.createPaymentIntent(params, key);
                if (!answer.undecided() || attempt == 2) {
                    return answer;
                }
                log.warn("payment {}: create answered {} {}, retrying with the same key", id, answer.status(), answer.errorCode());
            } catch (RestClientException noAnswer) {
                log.warn("payment {}: no answer to the create (attempt {}): {}", id, attempt, noAnswer.getMessage());
                if (attempt == 2) {
                    return null;
                }
            }
            sleep(300);
        }
    }

    private PaymentView beginChallenge(UUID id, JsonNode pi) {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "redirect");
        action.put("url", pi.path("next_action").path("redirect_to_url").path("url").asText());
        payments.startRedirectChallenge(id, pi.path("id").asText());
        merchant.notify(id, PaymentStatus.PENDING_CHALLENGE, action);
        return view(id, action);
    }

    // ------------------------------------------------------------------ the shopper comes back

    /** The browser reached return_url: Stripe's word is the PaymentIntent itself (the return carries no outcome). */
    public PaymentStatus onReturn(UUID paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> new UnknownPaymentException("payment " + paymentId));
        if (payment.pspReference() == null) {
            return payment.status();
        }
        StripeResponse answer = stripe.retrievePaymentIntent(payment.pspReference());
        if (answer.ok() && answer.paymentIntent() != null) {
            applyInTx(paymentId, answer.paymentIntent(), "return");
        } else {
            log.warn("payment {}: could not retrieve {} on return: {}", paymentId, payment.pspReference(), answer.status());
        }
        return payments.findById(paymentId).map(Payment::status).orElse(payment.status());
    }

    // ------------------------------------------------------------------ webhooks

    /** One event whose signature was already checked. */
    public void onEvent(JsonNode event) {
        String type = event.path("type").asText("");
        JsonNode object = event.path("data").path("object");
        boolean isIntent = "payment_intent".equals(object.path("object").asText());
        String piId = isIntent ? object.path("id").asText(null) : object.path("payment_intent").asText(null);
        UUID paymentId = paymentIdFor(isIntent ? object : null, piId);
        boolean success = Reconciler.SUCCESS_CODES.contains(type);

        Reconciler.Release release = tx.execute(s -> {
            Payment payment = paymentId == null ? null : payments.lockByIdIfExists(paymentId).orElse(null);
            boolean fresh = reconciler.record(payment == null ? null : payment.id(), type, success, piId == null ? "" : piId,
                    paymentId == null ? null : paymentId.toString(), event);
            if (!fresh) {
                log.info("Stripe event {} for {} already received: duplicate delivery", type, piId);
            }
            if (payment == null || !isIntent) {
                return null;
            }
            return apply(payment, object, "webhook " + type);
        });
        reconciler.release(release);
    }

    private UUID paymentIdFor(JsonNode intent, String piId) {
        String fromMetadata = intent == null ? null : intent.path("metadata").path("payment_id").asText(null);
        if (fromMetadata != null) {
            try {
                return UUID.fromString(fromMetadata);
            } catch (IllegalArgumentException notOurs) {
                return null;
            }
        }
        return piId == null ? null : payments.findByPspReference(piId).map(Payment::id).orElse(null);
    }

    // ------------------------------------------------------------------ pending timeout

    /** No outcome in time: refuse the payment, then cancel the PaymentIntent and read what the cancel says. */
    public void onPendingTimeout(Payment payment) {
        if (!reconciler.endWithoutAnswer(payment.id(), PaymentStatus.REFUSED, PaymentStatus.PENDING_CHALLENGE,
                "Stripe pending timeout")) {
            return;
        }
        log.info("payment {}: no Stripe outcome within {}: refused, cancelling {}", payment.id(),
                properties.stripe().pendingTimeout(), payment.pspReference());
        StripeResponse answer;
        try {
            answer = stripe.cancelPaymentIntent(payment.pspReference(), payment.id() + ":cancel");
        } catch (RestClientException noAnswer) {
            log.error("payment {}: no answer to the cancel; the marker stays for a later success webhook", payment.id());
            return;
        }
        JsonNode pi = answer.paymentIntent();
        String piStatus = pi == null ? "" : pi.path("status").asText("");
        if (answer.ok() && "canceled".equals(piStatus)) {
            if (wasAuthorised(pi.path("latest_charge"))) {
                // an authorisation that never reached paygate: the cancel released it (measured: the charge stays succeeded)
                tx.executeWithoutResult(s -> {
                    Payment locked = payments.lockById(payment.id());
                    if (reconciler.claim(locked, payment.pspReference()) != null) {
                        reconciler.recordCancel(payment.id(), payment.pspReference(), "CANCELED", "released at the pending timeout");
                    }
                });
            } else {
                markers.reconcile(payment.id());   // definitive now: canceled, nothing was authorised
            }
            return;
        }
        if ("succeeded".equals(piStatus) || "requires_capture".equals(piStatus)) {
            // Measured: a cancel that loses to the shopper's Authorize gets 400 unexpected_state with the PaymentIntent
            // already succeeded. That is a success, not an error.
            log.warn("payment {}: the cancel lost, Stripe says {}", payment.id(), piStatus);
            tx.executeWithoutResult(s -> reconciler.claim(payments.lockById(payment.id()), payment.pspReference()));
            return;
        }
        log.error("payment {}: cancel answered {} {} (PaymentIntent {}); the marker stays", payment.id(), answer.status(),
                answer.errorCode(), piStatus);
    }

    /** After paygate's cancel: was the payment authorised? */
    private boolean wasAuthorised(JsonNode latestCharge) {
        return latestCharge != null && !latestCharge.isNull() && !latestCharge.isMissingNode();
    }

    // ------------------------------------------------------------------ applying what Stripe says

    private void applyInTx(UUID id, JsonNode pi, String source) {
        reconciler.release(tx.execute(s -> apply(payments.lockById(id), pi, source)));
    }

    /**
     * Inside a transaction holding the payment's lock: what the PaymentIntent (or a webhook's snapshot of it) says.
     * Returns an authorisation to release after the commit, if any.
     */
    private Reconciler.Release apply(Payment payment, JsonNode pi, String source) {
        String status = pi.path("status").asText("");
        boolean authorised = "requires_capture".equals(status) || "succeeded".equals(status);
        if (authorised && markers.unreconciled(payment.id()) > 0) {
            // paygate already ended this payment itself (lost create answer, timeout): the reconciler decides
            return reconciler.reconcileSuccess(payment, pi.path("id").asText(null));
        }
        switch (status) {
            case "requires_capture", "succeeded" -> finish(payment.id(), PaymentStatus.COMPLETED, pi.path("id").asText(null), source);
            case "requires_payment_method" -> {
                if (!pi.path("last_payment_error").isMissingNode() && !pi.path("last_payment_error").isNull()) {
                    finish(payment.id(), PaymentStatus.REFUSED, pi.path("id").asText(null), source);
                }
            }
            case "requires_action" -> {
                if (payment.status().isFinal()) {
                    // a stale snapshot moves the payment back
                    log.warn("payment {}: {} (stale requires_action) moves it back from {}", payment.id(), source, payment.status());
                    payments.setStatus(payment.id(), PaymentStatus.PENDING_CHALLENGE, null);
                }
            }
            default -> log.info("payment {}: {} says {}, nothing to do", payment.id(), source, status);
        }
        return null;
    }

    private void finishInTx(UUID id, PaymentStatus status, String pspReference, String source) {
        tx.executeWithoutResult(s -> {
            payments.lockById(id);
            finish(id, status, pspReference, source);
        });
    }

    private void finish(UUID id, PaymentStatus status, String pspReference, String source) {
        if (fixes.on(Fixes.S1)) {
            // S1 fix: the return (possibly reloaded) and the webhooks race; only the first final outcome counts.
            if (payments.finishIfOpen(id, status, pspReference)) {
                merchant.notify(id, status, null);
            } else {
                log.info("payment {} already final: {} from {} not applied again", id, status, source);
            }
            return;
        }
        payments.setStatus(id, status, pspReference);
        merchant.notify(id, status, null);
    }

    // ------------------------------------------------------------------ helpers

    private PaymentView view(UUID id, JsonNode action) {
        Payment payment = payments.findById(id).orElseThrow(() -> new UnknownPaymentException("payment " + id));
        return new PaymentView(id, payment.status(), action, null);
    }

    private Map<String, Object> createParams(UUID id, StartPaymentRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amount", request.amount().value());
        params.put("currency", request.amount().currency().toLowerCase());
        params.put("confirm", true);
        params.put("capture_method", "manual");
        params.put("payment_method", paymentMethod(request));
        params.put("allowed_payment_method_types", List.of("card"));
        params.put("payment_method_options", Map.of("card", Map.of("request_three_d_secure", "challenge")));
        String returnBase = properties.stripe().returnBaseUrl();
        params.put("return_url", (returnBase == null || returnBase.isBlank() ? properties.publicBaseUrl() : returnBase)
                + "/stripe/return/" + id);
        params.put("metadata", Map.of("payment_id", id.toString(), "merchant_reference", request.merchantReference()));
        return params;
    }

    private static String paymentMethod(StartPaymentRequest request) {
        Object id = request.paymentMethod() == null ? null : request.paymentMethod().get("id");
        return id == null ? DEFAULT_PAYMENT_METHOD : id.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
