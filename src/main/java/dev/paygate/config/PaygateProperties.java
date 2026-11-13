package dev.paygate.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "paygate")
public record PaygateProperties(
        @NotBlank String publicBaseUrl,
        @NotNull Adyen adyen,
        @NotNull Stripe stripe,
        @NotNull Challenge challenge,
        @NotNull Method method,
        @NotNull FixDefaults fixes,
        boolean demoEndpoints) {

    /** @param captureDir optional; when set, every Adyen request and answer is saved there as JSON (never the API key) */
    public record Adyen(@NotBlank String baseUrl, String apiKey, @NotBlank String merchantAccount,
                        @NotNull Duration connectTimeout, @NotNull Duration readTimeout, @NotNull Webhook webhook,
                        String captureDir) {

        @Override
        public String toString() {
            return "Adyen[baseUrl=" + baseUrl + ", merchantAccount=" + merchantAccount + ", apiKey hidden]";
        }
    }

    /** Webhook credentials as set in the Customer Area. Empty values make paygate reject every webhook. */
    public record Webhook(String username, String password, String hmacKey) {

        @Override
        public String toString() {
            return "Webhook[credentials hidden]";
        }
    }

    /**
     * Stripe: PaymentIntents with Stripe's own 3DS (a redirect challenge). {@code pendingTimeout}: how long paygate
     * waits for the outcome before it cancels the PaymentIntent (measured: an opened challenge fails after ~15 min,
     * one never opened stays payable for days). {@code returnBaseUrl}: where Stripe sends the shopper's browser back; empty
     * = public-base-url. The browser makes that request itself, so a local address works where a tunnel shows a warning
     * page for browser GETs (ngrok's free domains do).
     */
    public record Stripe(@NotBlank String baseUrl, String apiKey, String webhookSecret, @NotNull Duration connectTimeout,
                         @NotNull Duration readTimeout, @NotNull Duration pendingTimeout, String returnBaseUrl) {

        @Override
        public String toString() {
            return "Stripe[baseUrl=" + baseUrl + ", apiKey and webhookSecret hidden]";
        }
    }

    public record Challenge(@NotNull Duration timeout, long watchdogScanIntervalMs) {
    }

    /** How long the issuer gets for the 3DS method step before paygate refuses the payment itself. */
    public record Method(@NotNull Duration timeout) {
    }

    /** Start values of the fix switches; {@link Fixes} holds the live values. */
    public record FixDefaults(
            boolean c3,
            boolean s1) {
    }
}
