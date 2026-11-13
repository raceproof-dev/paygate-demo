package dev.paygate.adyen;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Adyen's HMAC signature on a standard-webhook NotificationRequestItem, as documented by Adyen:
 * <pre>
 *   payload   = pspReference:originalReference:merchantAccountCode:merchantReference:value:currency:eventCode:success
 *               (missing values are empty; inside a value "\" becomes "\\" and ":" becomes "\:")
 *   signature = base64(HMAC-SHA256(hex-decoded key, payload))   in additionalData.hmacSignature
 * </pre>
 */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static boolean isValid(JsonNode item, String hmacKeyHex) {
        String given = item.path("additionalData").path("hmacSignature").asText("");
        if (given.isEmpty() || hmacKeyHex == null || hmacKeyHex.isBlank()) {
            return false;
        }
        String expected = sign(item, hmacKeyHex);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sign(JsonNode item, String hmacKeyHex) {
        String payload = String.join(":",
                field(item, "pspReference"),
                field(item, "originalReference"),
                field(item, "merchantAccountCode"),
                field(item, "merchantReference"),
                field(item.path("amount"), "value"),
                field(item.path("amount"), "currency"),
                field(item, "eventCode"),
                field(item, "success"));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(hmacKeyHex.trim()), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        return value.asText().replace("\\", "\\\\").replace(":", "\\:");
    }
}
