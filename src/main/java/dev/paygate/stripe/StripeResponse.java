package dev.paygate.stripe;

import com.fasterxml.jackson.databind.JsonNode;

/** An HTTP answer from Stripe: status code plus the JSON body (null if empty or not JSON). */
public record StripeResponse(int status, JsonNode body) {

    public boolean ok() {
        return status == 200;
    }

    /** The PaymentIntent in the answer: the body itself, or, for an error, the one Stripe attaches to it. */
    public JsonNode paymentIntent() {
        if (body == null) {
            return null;
        }
        if ("payment_intent".equals(body.path("object").asText())) {
            return body;
        }
        JsonNode attached = body.path("error").path("payment_intent");
        return attached.isObject() ? attached : null;
    }

    /** error.code, e.g. card_declined, payment_intent_unexpected_state, idempotency_key_in_use. */
    public String errorCode() {
        if (body == null) {
            return null;
        }
        JsonNode code = body.path("error").path("code");
        return code.isTextual() ? code.asText() : null;
    }

    public String errorType() {
        if (body == null) {
            return null;
        }
        JsonNode type = body.path("error").path("type");
        return type.isTextual() ? type.asText() : null;
    }

    /** No definitive answer: a server error, or 409 (the same idempotency key is still in progress). */
    public boolean undecided() {
        return status >= 500 || status == 409;
    }
}
