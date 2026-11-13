package dev.paygate.stripe;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Stripe's webhook signature: {@code Stripe-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.<raw body>">}, keyed
 * with the whole whsec_ secret. Several v1 entries may be present (secret rotation); one match is enough. A signature
 * older than the tolerance is rejected, so a captured request cannot be replayed later.
 */
public final class StripeSignature {

    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private StripeSignature() {
    }

    public static boolean isValid(byte[] payload, String header, String secret, Instant now) {
        if (header == null || secret == null || secret.isBlank()) {
            return false;
        }
        Long t = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t") && t == null) {
                try {
                    t = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (t == null || signatures.isEmpty()) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(t), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] expected = sign(payload, secret, t).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** The hex v1 signature for a payload signed at {@code t}. */
    public static String sign(byte[] payload, String secret, long t) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
