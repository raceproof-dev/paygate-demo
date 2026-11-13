package dev.paygate.adyen;

import com.fasterxml.jackson.databind.JsonNode;

/** An HTTP answer from Adyen: status code plus the JSON body (null if the body was empty or not JSON). */
public record AdyenResponse(int status, JsonNode body) {

    public boolean ok() {
        return status == 200;
    }

    public boolean accepted() {
        return status == 200 || status == 201 || status == 202;
    }

    public String resultCode() {
        return text("resultCode");
    }

    public String errorCode() {
        return text("errorCode");
    }

    public String pspReference() {
        return text("pspReference");
    }

    public JsonNode action() {
        return body == null ? null : body.get("action");
    }

    /**
     * errorCode 704, "request already processed or in progress": the 3DS step was already submitted. Measured on the
     * Adyen test environment: 409 while the first submission with the same idempotency key is still in progress,
     * 422 for a submission with a different key. (Once the first answered, the same key gets that answer replayed.)
     */
    public boolean stepAlreadySubmitted() {
        return (status == 409 || status == 422) && "704".equals(errorCode());
    }

    private String text(String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        return body.get(field).asText();
    }
}
