package dev.paygate.payment;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paygate.adyen.AdyenClient;
import dev.paygate.adyen.AdyenResponse;
import dev.paygate.adyen.ThreeDsCodec;
import dev.paygate.config.Fixes;
import dev.paygate.config.PaygateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The native 3DS2 flow with Adyen:
 * <pre>
 *   POST /payments            -> IdentifyShopper (method step) or ChallengeShopper, or a final answer
 *   issuer posts method data  -> /payments/details with threeds2.fingerprint   -> ChallengeShopper or final
 *   issuer posts the CRes     -> /payments/details with threeds2.challengeResult -> final
 *   or the watchdog fires     -> /payments/details with transStatus U ("abandoned") -> final
 * </pre>
 * Both challenge paths use the same idempotency key, so Adyen accepts only one of them.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final AdyenClient adyen;
    private final ThreeDsCodec codec;
    private final PaymentRepository payments;
    private final MerchantNotifier merchant;
    private final Reconciler reconciler;
    private final Fixes fixes;
    private final PaygateProperties properties;

    public PaymentService(AdyenClient adyen, ThreeDsCodec codec, PaymentRepository payments, MerchantNotifier merchant,
                          Reconciler reconciler, Fixes fixes, PaygateProperties properties) {
        this.adyen = adyen;
        this.codec = codec;
        this.payments = payments;
        this.merchant = merchant;
        this.reconciler = reconciler;
        this.fixes = fixes;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ start

    public PaymentView start(StartPaymentRequest request) {
        UUID id = request.transactionId() != null ? request.transactionId() : UUID.randomUUID();
        payments.insert(id, request.merchantReference(), request.amount().value(), request.amount().currency());

        AdyenResponse answer;
        try {
            answer = adyen.payments(paymentsRequest(id, request), id.toString());
        } catch (RestClientException noAnswer) {
            fail(id, "no answer to /payments: " + noAnswer.getMessage());
            return view(id, null);
        }
        if (!answer.ok()) {
            fail(id, "/payments answered " + answer.status());
            return view(id, null);
        }
        return switch (String.valueOf(answer.resultCode())) {
            case "IdentifyShopper" -> beginThreeDs(id, PaymentStatus.PENDING_METHOD, answer.action());
            case "ChallengeShopper" -> beginThreeDs(id, PaymentStatus.PENDING_CHALLENGE, answer.action());
            default -> {
                applyFinalAnswer(id, answer, "/payments");
                yield view(id, null);
            }
        };
    }

    // ------------------------------------------------------------------ method step

    public void onMethodData(String threeDsMethodData) {
        String transId = codec.decodeBase64Url(threeDsMethodData).path("threeDSServerTransID").asText(null);
        Payment payment = paymentFor(transId);
        if (payment.status() != PaymentStatus.PENDING_METHOD) {
            log.info("method data for payment {} in status {}: ignored", payment.id(), payment.status());
            return;
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("threeDSServerTransID", transId);
        fingerprint.put("threeDSCompInd", "Y");

        AdyenResponse answer;
        try {
            answer = adyen.paymentDetails(details(payment, "threeds2.fingerprint", codec.encodeBase64Url(fingerprint)),
                    payment.id() + ":fingerprint");
        } catch (RestClientException noAnswer) {
            fail(payment.id(), "no answer to the fingerprint: " + noAnswer.getMessage());
            return;
        }
        if (answer.ok() && "ChallengeShopper".equals(answer.resultCode())) {
            beginThreeDs(payment.id(), PaymentStatus.PENDING_CHALLENGE, answer.action());
            return;
        }
        applyFinalAnswer(payment.id(), answer, "fingerprint");
    }

    // ------------------------------------------------------------------ challenge step

    /** The issuer's ACS posted the shopper's challenge result (CRes) to paygate. */
    public void onChallengeResult(String cres) {
        JsonNode result = codec.decodeBase64Url(cres);
        Payment payment = paymentFor(result.path("threeDSServerTransID").asText(null));
        submitChallenge(payment, result.path("transStatus").asText("U"), "CRes");
    }

    /** The shopper did not finish the challenge in time: tell Adyen it was abandoned. */
    public void onChallengeTimeout(Payment payment) {
        submitChallenge(payment, "U", "watchdog");
    }

    private void submitChallenge(Payment payment, String transStatus, String source) {
        AdyenResponse answer;
        try {
            answer = adyen.paymentDetails(
                    details(payment, "threeds2.challengeResult", codec.encodeBase64Url(Map.of("transStatus", transStatus))),
                    payment.id() + ":challenge");
        } catch (RestClientException noAnswer) {
            fail(payment.id(), source + ": no answer to the challenge submission: " + noAnswer.getMessage());
            return;
        }
        if (answer.stepAlreadySubmitted() && fixes.on(Fixes.C3)) {
            // C3 fix: the other path (CRes or watchdog) already submitted the challenge result; its answer decides.
            log.info("{} for payment {}: Adyen already has the challenge result (704), nothing to do", source, payment.id());
            return;
        }
        applyFinalAnswer(payment.id(), answer, source);
    }

    // ------------------------------------------------------------------ outcomes

    private PaymentView beginThreeDs(UUID id, PaymentStatus status, JsonNode action) {
        String transId = codec.decodeActionToken(action.path("token").asText()).path("threeDSServerTransID").asText(null);
        payments.startThreeDs(id, status, transId, action.path("paymentData").asText(null));
        merchant.notify(id, status, action);
        return view(id, action);
    }

    private void applyFinalAnswer(UUID id, AdyenResponse answer, String source) {
        if (answer.ok() && "Authorised".equals(answer.resultCode())) {
            finish(id, PaymentStatus.COMPLETED, answer.pspReference());
        } else if (answer.ok() && "Refused".equals(answer.resultCode())) {
            finish(id, PaymentStatus.REFUSED, answer.pspReference());
        } else {
            fail(id, source + ": Adyen answered " + answer.status() + " " + answer.resultCode() + " " + answer.errorCode());
        }
    }

    private void finish(UUID id, PaymentStatus status, String pspReference) {
        if (fixes.on(Fixes.C3)) {
            // C3 fix, second half: Adyen replays the first answer to a same-key submission that arrives after it, so the
            // losing path can receive the winner's answer. Only the first final answer is recorded and told to the merchant.
            if (payments.finishIfOpen(id, status, pspReference)) {
                merchant.notify(id, status, null);
            } else {
                log.info("payment {} already final: answer {} not applied again", id, status);
            }
            return;
        }
        payments.setStatus(id, status, pspReference);
        merchant.notify(id, status, null);
    }

    /** No definitive answer from Adyen: FAILED, plus a marker so a later success can be reconciled. */
    private void fail(UUID id, String reason) {
        log.warn("payment {} failed: {}", id, reason);
        if (fixes.on(Fixes.C3)) {
            // a payment that already ended keeps its outcome
            if (!reconciler.endWithoutAnswerWhen(id, PaymentStatus.FAILED, current -> !current.isFinal(), reason)) {
                log.info("payment {} already final: not marked FAILED", id);
            }
            return;
        }
        reconciler.endWithoutAnswer(id, PaymentStatus.FAILED, null, reason);
    }

    /** The issuer never finished the method step: paygate refuses the payment itself (an artificial refusal). */
    public void onMethodTimeout(Payment payment) {
        if (reconciler.endWithoutAnswer(payment.id(), PaymentStatus.REFUSED, PaymentStatus.PENDING_METHOD,
                "method step timed out")) {
            log.info("method step of payment {} timed out: refused by paygate", payment.id());
        }
    }

    // ------------------------------------------------------------------ helpers

    public PaymentView view(UUID id) {
        return view(id, null);
    }

    private PaymentView view(UUID id, JsonNode action) {
        Payment payment = payments.findById(id).orElseThrow(() -> new UnknownPaymentException("payment " + id));
        PaymentStatus status = payment.status();
        boolean pending = status == PaymentStatus.PENDING_METHOD || status == PaymentStatus.PENDING_CHALLENGE;
        if (action == null && pending) {
            // a frontend polling after the method step needs the challenge action, which only the fingerprint answer had
            action = merchant.lastDetail(id, status).orElse(null);
        }
        String methodNotificationUrl = status == PaymentStatus.PENDING_METHOD ? properties.publicBaseUrl() + "/3ds/method" : null;
        return new PaymentView(id, status, pending ? action : null, methodNotificationUrl);
    }

    private Payment paymentFor(String threeDsTransId) {
        if (threeDsTransId == null) {
            throw new UnknownPaymentException("3DS message without threeDSServerTransID");
        }
        return payments.findByThreeDsTransId(threeDsTransId)
                .orElseThrow(() -> new UnknownPaymentException("no payment for threeDSServerTransID " + threeDsTransId));
    }

    private Map<String, Object> details(Payment payment, String field, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentData", payment.paymentData());
        body.put("details", Map.of(field, value));
        return body;
    }

    private Map<String, Object> paymentsRequest(UUID id, StartPaymentRequest request) {
        String base = properties.publicBaseUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", properties.adyen().merchantAccount());
        body.put("reference", id.toString());   // Adyen echoes it in webhooks as merchantReference: how paygate finds the payment
        body.put("amount", Map.of("value", request.amount().value(), "currency", request.amount().currency()));
        body.put("paymentMethod", request.paymentMethod() != null ? request.paymentMethod() : testCard());
        body.put("channel", "Web");
        body.put("origin", base);
        body.put("returnUrl", base + "/return");
        body.put("authenticationData", Map.of("threeDSRequestData", Map.of("nativeThreeDS", "preferred")));
        body.put("threeDS2RequestData", Map.of("deviceChannel", "browser", "notificationURL", base + "/3ds/challenge"));
        body.put("browserInfo", Map.of(
                "userAgent", "Mozilla/5.0 (paygate demo)", "acceptHeader", "text/html", "language", "en-US",
                "colorDepth", 24, "screenHeight", 1080, "screenWidth", 1920, "timeZoneOffset", 0, "javaEnabled", false));
        return body;
    }

    /** Adyen accepts unencrypted test card data with the test_ prefix in its test environment. */
    private static Map<String, Object> testCard() {
        return Map.of("type", "scheme",
                "encryptedCardNumber", "test_5454545454545454",
                "encryptedExpiryMonth", "test_03",
                "encryptedExpiryYear", "test_2030",
                "encryptedSecurityCode", "test_737");
    }
}
