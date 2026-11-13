package dev.paygate.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * @param transactionId optional; the caller's own id for the payment (the lab passes one so it can find the row)
 * @param psp           optional; "adyen" (default) or "stripe"
 * @param paymentMethod optional. Adyen: sent as is, default an Adyen test card. Stripe: {"id": "pm_..."}, default
 *                      pm_card_threeDSecure2Required (a test card that always asks for a challenge)
 */
public record StartPaymentRequest(
        UUID transactionId,
        @Pattern(regexp = "adyen|stripe") String psp,
        @NotBlank String merchantReference,
        @NotNull @Valid Amount amount,
        Map<String, Object> paymentMethod) {

    public record Amount(@Positive long value, @NotBlank @Size(min = 3, max = 3) String currency) {
    }
}
