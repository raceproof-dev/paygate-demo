package dev.paygate.adyen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeDsCodecTest {

    private final ThreeDsCodec codec = new ThreeDsCodec(new ObjectMapper());

    @Test
    void decodesAnAdyenActionToken() {
        // Adyen's token: JSON with escaped slashes, standard base64 with padding
        String json = "{\"threeDSMethodUrl\":\"http:\\/\\/acs\\/method\",\"threeDSServerTransID\":\"t-1\"}";
        String token = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        JsonNode decoded = codec.decodeActionToken(token);

        assertThat(decoded.path("threeDSServerTransID").asText()).isEqualTo("t-1");
        assertThat(decoded.path("threeDSMethodUrl").asText()).isEqualTo("http://acs/method");
    }

    @Test
    void encodesCompactBase64UrlWithoutPaddingInKeyOrder() {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("threeDSServerTransID", "t-1");
        fingerprint.put("threeDSCompInd", "Y");

        String encoded = codec.encodeBase64Url(fingerprint);

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo("{\"threeDSServerTransID\":\"t-1\",\"threeDSCompInd\":\"Y\"}");
    }

    @Test
    void decodesUnpaddedBase64Url() {
        String cres = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"threeDSServerTransID\":\"t-2\",\"transStatus\":\"Y\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(codec.decodeBase64Url(cres).path("transStatus").asText()).isEqualTo("Y");
    }
}
