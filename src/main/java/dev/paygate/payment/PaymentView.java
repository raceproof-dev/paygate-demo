package dev.paygate.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * What the merchant's frontend gets back: the status and, while 3DS is in progress, the action to render.
 *
 * @param threeDsMethodNotificationUrl during the method step: where the issuer's ACS posts back; the frontend puts it into
 *                                     the threeDSMethodData it sends to the ACS (Adyen's token carries Adyen's own URL)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentView(UUID transactionId, PaymentStatus status, JsonNode action, String threeDsMethodNotificationUrl) {
}
