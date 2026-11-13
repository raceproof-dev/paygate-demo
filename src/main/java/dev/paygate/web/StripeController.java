package dev.paygate.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paygate.config.PaygateProperties;
import dev.paygate.payment.PaymentStatus;
import dev.paygate.payment.StripePaymentService;
import dev.paygate.stripe.StripeSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Stripe's two ways back to paygate: the shopper's browser (return_url) and webhooks. */
@RestController
public class StripeController {

    private static final Logger log = LoggerFactory.getLogger(StripeController.class);

    private final StripePaymentService service;
    private final PaygateProperties properties;
    private final ObjectMapper objectMapper;

    public StripeController(StripePaymentService service, PaygateProperties properties, ObjectMapper objectMapper) {
        this.service = service;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Where Stripe's hosted 3DS page sends the browser. It says nothing about the outcome: paygate asks Stripe. */
    @GetMapping(path = "/stripe/return/{paymentId}", produces = MediaType.TEXT_HTML_VALUE)
    public String returned(@PathVariable UUID paymentId) {
        PaymentStatus status = service.onReturn(paymentId);
        return "<!doctype html><p>Payment " + status.name().toLowerCase().replace('_', ' ') + ". You can close this window.</p>";
    }

    /** Signed with the endpoint's whsec_ secret. Non-2xx makes Stripe retry later (measured: ~1 h and ~3 h). */
    @PostMapping(path = "/webhooks/stripe", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> webhook(@RequestHeader(name = "Stripe-Signature", required = false) String signature,
                                          @RequestBody byte[] body) {
        String secret = properties.stripe().webhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("Stripe webhook rejected: STRIPE_WEBHOOK_SECRET is not set");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("webhook secret not configured");
        }
        if (!StripeSignature.isValid(body, signature, secret, Instant.now())) {
            log.warn("Stripe webhook rejected: missing, wrong or expired signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("not JSON");
        }
        service.onEvent(event);
        return ResponseEntity.ok("ok");
    }
}
