package dev.paygate.payment;

public enum PaymentStatus {
    CREATED,
    PENDING_METHOD,
    PENDING_CHALLENGE,
    COMPLETED,
    REFUSED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == REFUSED || this == FAILED;
    }
}
