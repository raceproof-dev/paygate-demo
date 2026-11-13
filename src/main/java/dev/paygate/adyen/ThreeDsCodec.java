package dev.paygate.adyen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The encodings of native 3DS2 with Adyen:
 * <ul>
 *   <li>Adyen's action.token: JSON, standard base64 with padding;</li>
 *   <li>threeDSMethodData, CRes, and what paygate sends back (threeds2.fingerprint / threeds2.challengeResult):
 *       compact JSON, base64url without padding.</li>
 * </ul>
 */
@Component
public class ThreeDsCodec {

    private final ObjectMapper objectMapper;

    public ThreeDsCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode decodeActionToken(String token) {
        return readJson(Base64.getMimeDecoder().decode(token));
    }

    public JsonNode decodeBase64Url(String value) {
        return readJson(Base64.getUrlDecoder().decode(pad(value.trim())));
    }

    /** Keys are written in the map's iteration order (use a LinkedHashMap or Map.of with one entry). */
    public String encodeBase64Url(Map<String, ?> payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialise 3DS payload", e);
        }
    }

    private JsonNode readJson(byte[] bytes) {
        try {
            return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a JSON 3DS payload", e);
        }
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
