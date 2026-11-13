package dev.paygate.stripe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StripeSignatureTest {

    static final String SECRET = "whsec_test_secret_for_unit_tests";
    static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
    static final long T = 1_800_000_000L;
    static final Instant NOW = Instant.ofEpochSecond(T + 10);

    @Test
    void acceptsAMatchingSignature() {
        String header = "t=" + T + ",v1=" + StripeSignature.sign(BODY, SECRET, T);
        assertThat(StripeSignature.isValid(BODY, header, SECRET, NOW)).isTrue();
    }

    @Test
    void oneMatchingV1AmongSeveralIsEnough() {
        String header = "t=" + T + ",v1=" + "0".repeat(64) + ",v1=" + StripeSignature.sign(BODY, SECRET, T);
        assertThat(StripeSignature.isValid(BODY, header, SECRET, NOW)).isTrue();
    }

    @Test
    void rejectsATamperedBodyAWrongSecretAndAMissingHeader() {
        String header = "t=" + T + ",v1=" + StripeSignature.sign(BODY, SECRET, T);
        byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(StripeSignature.isValid(tampered, header, SECRET, NOW)).isFalse();
        assertThat(StripeSignature.isValid(BODY, header, "whsec_other", NOW)).isFalse();
        assertThat(StripeSignature.isValid(BODY, null, SECRET, NOW)).isFalse();
        assertThat(StripeSignature.isValid(BODY, "t=" + T, SECRET, NOW)).isFalse();
    }

    @Test
    void rejectsASignatureOutsideTheTolerance() {
        String header = "t=" + T + ",v1=" + StripeSignature.sign(BODY, SECRET, T);
        assertThat(StripeSignature.isValid(BODY, header, SECRET, Instant.ofEpochSecond(T + 301))).isFalse();
    }

    @Test
    void encodesNestedParametersAsStripeExpects() {
        String form = StripeClient.encode(new java.util.LinkedHashMap<>(java.util.Map.of(
                "payment_method_options", java.util.Map.of("card", java.util.Map.of("request_three_d_secure", "challenge")))));
        assertThat(form).isEqualTo("payment_method_options%5Bcard%5D%5Brequest_three_d_secure%5D=challenge");
        assertThat(StripeClient.encode(java.util.Map.of("expand", java.util.List.of("latest_charge"))))
                .isEqualTo("expand%5B%5D=latest_charge");
    }
}
