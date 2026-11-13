package dev.paygate.adyen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The example from Adyen's HMAC verification documentation (public test key and signature). */
    private static final String DOCS_KEY = "44782DEF547AAA06C910C43932B1EB0C71FC68D9D0C057550C48EC2ACF6BA056";
    private static final String DOCS_ITEM = """
            {"additionalData": {"hmacSignature": "coqCmt/IZ4E3CzPvMY8zTjQVL5hYJUiBRg8UU+iCWo0="},
             "amount": {"currency": "EUR", "value": 1130}, "pspReference": "7914073381342284",
             "eventCode": "AUTHORISATION", "eventDate": "2019-05-06T17:15:34.121+02:00",
             "merchantAccountCode": "TestMerchant", "operations": ["CANCEL", "CAPTURE", "REFUND"],
             "merchantReference": "TestPayment-1407325143704", "paymentMethod": "visa", "success": "true"}""";

    @Test
    void reproducesAdyensDocumentedSignature() throws Exception {
        JsonNode item = mapper.readTree(DOCS_ITEM);
        assertThat(WebhookSignature.sign(item, DOCS_KEY)).isEqualTo("coqCmt/IZ4E3CzPvMY8zTjQVL5hYJUiBRg8UU+iCWo0=");
        assertThat(WebhookSignature.isValid(item, DOCS_KEY)).isTrue();
    }

    @Test
    void rejectsATamperedItemAWrongKeyAndAMissingSignature() throws Exception {
        ObjectNode tampered = (ObjectNode) mapper.readTree(DOCS_ITEM);
        ((ObjectNode) tampered.get("amount")).put("value", 1131);
        assertThat(WebhookSignature.isValid(tampered, DOCS_KEY)).isFalse();

        assertThat(WebhookSignature.isValid(mapper.readTree(DOCS_ITEM), "00".repeat(32))).isFalse();

        ObjectNode unsigned = (ObjectNode) mapper.readTree(DOCS_ITEM);
        unsigned.remove("additionalData");
        assertThat(WebhookSignature.isValid(unsigned, DOCS_KEY)).isFalse();
        assertThat(WebhookSignature.isValid(mapper.readTree(DOCS_ITEM), "")).isFalse();
    }

    @Test
    void escapesColonsAndBackslashesInsideValues() throws Exception {
        String key = "ab".repeat(32);
        ObjectNode a = (ObjectNode) mapper.readTree(DOCS_ITEM);
        a.put("merchantReference", "x:y");
        ObjectNode b = (ObjectNode) mapper.readTree(DOCS_ITEM);
        b.put("merchantAccountCode", "TestMerchant:x");
        b.put("merchantReference", "y");                  // unescaped, both would read "TestMerchant:x:y"
        assertThat(WebhookSignature.sign(a, key)).isNotEqualTo(WebhookSignature.sign(b, key));
    }
}
