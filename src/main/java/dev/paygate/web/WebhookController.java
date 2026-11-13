package dev.paygate.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paygate.adyen.WebhookSignature;
import dev.paygate.config.PaygateProperties;
import dev.paygate.payment.Reconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Adyen standard webhooks. A batch is accepted only with the configured Basic-auth credentials and a valid HMAC
 * signature on every item; then each item is handled and paygate answers "[accepted]", as Adyen expects.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaygateProperties.Webhook settings;
    private final Reconciler reconciler;

    public WebhookController(PaygateProperties properties, Reconciler reconciler) {
        this.settings = properties.adyen().webhook();
        this.reconciler = reconciler;
    }

    @PostMapping(path = "/webhooks/adyen", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                          @RequestBody JsonNode notification) {
        if (!configured()) {
            log.error("webhook rejected: ADYEN_WEBHOOK_USERNAME, ADYEN_WEBHOOK_PASSWORD and ADYEN_WEBHOOK_HMAC_KEY must all be set");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("webhook credentials not configured");
        }
        if (!basicAuthMatches(authorization)) {
            log.warn("webhook rejected: wrong or missing Basic-auth credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic").body("unauthorized");
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode wrapper : notification.path("notificationItems")) {
            JsonNode item = wrapper.path("NotificationRequestItem");
            if (!WebhookSignature.isValid(item, settings.hmacKey())) {
                log.warn("webhook rejected: invalid HMAC signature on {} {}", item.path("eventCode").asText(),
                        item.path("pspReference").asText());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
            }
            items.add(item);
        }
        items.forEach(reconciler::onWebhookItem);
        return ResponseEntity.ok("[accepted]");
    }

    private boolean configured() {
        return notBlank(settings.username()) && notBlank(settings.password()) && notBlank(settings.hmacKey());
    }

    private boolean basicAuthMatches(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        byte[] given;
        try {
            given = Base64.getDecoder().decode(authorization.substring(6).trim());
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        byte[] expected = (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
