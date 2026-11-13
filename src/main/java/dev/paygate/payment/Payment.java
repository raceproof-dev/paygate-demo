package dev.paygate.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(
        UUID id,
        String psp,
        String merchantReference,
        long amountValue,
        String currency,
        PaymentStatus status,
        String threeDsTransId,
        String paymentData,
        String pspReference,
        OffsetDateTime challengeStartedAt) {
}
