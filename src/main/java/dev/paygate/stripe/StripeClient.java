package dev.paygate.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paygate.config.PaygateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Stripe client: create (and confirm) a PaymentIntent, retrieve it, cancel it. Stripe takes form-encoded
 * parameters (nested as a[b]=c) and answers JSON. Non-2xx answers are returned, not thrown, because paygate must look at
 * them (e.g. a 402 decline or a 400 that carries the PaymentIntent); timeouts and connection errors throw.
 */
@Component
public class StripeClient {

    private static final Logger log = LoggerFactory.getLogger(StripeClient.class);
    /** Client secrets (pi_..._secret_...), publishable keys and account ids: the 3DS page URL carries all three. */
    private static final Pattern SECRET = Pattern.compile(
            "[A-Za-z0-9]+_[A-Za-z0-9]+_secret_[A-Za-z0-9]+|pk_(?:test|live)_[A-Za-z0-9]+|acct_[A-Za-z0-9]+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Path captureDir;
    private final AtomicLong captureSeq = new AtomicLong();

    public StripeClient(PaygateProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String dir = properties.adyen().captureDir();      // PAYGATE_CAPTURE_DIR: one folder for both PSPs
        this.captureDir = StringUtils.hasText(dir) ? Path.of(dir) : null;
        PaygateProperties.Stripe stripe = properties.stripe();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(stripe.connectTimeout());
        requestFactory.setReadTimeout(stripe.readTimeout());
        this.restClient = builder
                .baseUrl(stripe.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(stripe.apiKey())) {
                        headers.setBearerAuth(stripe.apiKey());
                    }
                })
                .build();
    }

    public StripeResponse createPaymentIntent(Map<String, Object> params, String idempotencyKey) {
        return post("/v1/payment_intents", params, idempotencyKey);
    }

    /** With the latest charge expanded: after a cancel only the charge tells a released authorisation from a decline. */
    public StripeResponse retrievePaymentIntent(String id) {
        long started = System.nanoTime();
        StripeResponse answer = restClient.get()
                .uri("/v1/payment_intents/{id}?expand[]=latest_charge", id)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new StripeResponse(response.getStatusCode().value(), json(response)));
        capture("GET", "/v1/payment_intents/" + id, null, null, answer, started);
        return answer;
    }

    public StripeResponse cancelPaymentIntent(String id, String idempotencyKey) {
        return post("/v1/payment_intents/" + id + "/cancel", Map.of("expand", List.of("latest_charge")), idempotencyKey);
    }

    private StripeResponse post(String path, Map<String, Object> params, String idempotencyKey) {
        long started = System.nanoTime();
        StripeResponse answer;
        try {
            answer = restClient.post()
                    .uri(path)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(encode(params))
                    .exchange((request, response) -> new StripeResponse(response.getStatusCode().value(), json(response)));
        } catch (RuntimeException noAnswer) {
            capture("POST", path, idempotencyKey, params, null, started);
            throw noAnswer;
        }
        capture("POST", path, idempotencyKey, params, answer, started);
        return answer;
    }

    /**
     * With PAYGATE_CAPTURE_DIR set: saves one exchange as JSON. Headers (the API key) are not saved, and every client
     * secret is replaced, also inside URLs (the 3DS page and the return carry it).
     */
    private void capture(String method, String path, String idempotencyKey, Map<String, Object> params,
                         StripeResponse answer, long started) {
        if (captureDir == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("at", Instant.now().toString());
        record.put("psp", "stripe");
        record.put("method", method);
        record.put("path", path);
        record.put("idempotencyKey", idempotencyKey);
        record.put("ms", (System.nanoTime() - started) / 1_000_000);
        record.put("request", params);
        record.put("status", answer == null ? null : answer.status());
        record.put("answer", answer == null ? null : answer.body());
        String name = String.format("%d-%04d-stripe_%s.json", System.currentTimeMillis(), captureSeq.incrementAndGet(),
                path.replaceAll("/v1/payment_intents/?[^/]*", "pi").replaceAll("[^a-z]+", "_").replaceAll("^_|_$", ""));
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
            Files.createDirectories(captureDir);
            Files.writeString(captureDir.resolve(name), SECRET.matcher(json).replaceAll("<redacted>"));
        } catch (IOException e) {
            log.warn("could not save the Stripe capture {}: {}", name, e.toString());
        }
    }

    private JsonNode json(ClientHttpResponse response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
    }

    /** Stripe's form encoding: nested maps as a[b]=c, lists as a[]=x. */
    static String encode(Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> walk(key, value, pairs));
        return String.join("&", pairs);
    }

    private static void walk(String key, Object value, List<String> pairs) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> walk(key + "[" + k + "]", v, pairs));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> walk(key + "[]", v, pairs));
        } else if (value != null) {
            pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        }
    }
}
