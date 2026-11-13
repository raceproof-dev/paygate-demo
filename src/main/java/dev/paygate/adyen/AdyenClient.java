package dev.paygate.adyen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.paygate.config.PaygateProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Adyen Checkout client: /payments, /payments/details and cancels. Non-2xx answers are returned, not thrown, because
 * paygate must look at them (e.g. 422/704). Timeouts and connection errors throw a RestClientException.
 */
@Component
public class AdyenClient {

    private static final Logger log = LoggerFactory.getLogger(AdyenClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Path captureDir;
    private final AtomicLong captureSeq = new AtomicLong();

    public AdyenClient(PaygateProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        PaygateProperties.Adyen adyen = properties.adyen();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(adyen.connectTimeout());
        requestFactory.setReadTimeout(adyen.readTimeout());
        this.restClient = builder
                .baseUrl(adyen.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(adyen.apiKey())) {
                        headers.set("X-API-Key", adyen.apiKey());
                    }
                })
                .build();
        this.captureDir = StringUtils.hasText(adyen.captureDir()) ? Path.of(adyen.captureDir()) : null;
    }

    public AdyenResponse payments(Map<String, Object> body, String idempotencyKey) {
        return post("/payments", body, idempotencyKey);
    }

    public AdyenResponse paymentDetails(Map<String, Object> body, String idempotencyKey) {
        return post("/payments/details", body, idempotencyKey);
    }

    /** Cancels an authorisation. Adyen answers 201 "received"; the outcome would follow as a CANCELLATION webhook. */
    public AdyenResponse cancel(String paymentPspReference, Map<String, Object> body, String idempotencyKey) {
        return post("/payments/{psp}/cancels", body, idempotencyKey, paymentPspReference);
    }

    private AdyenResponse post(String path, Map<String, Object> body, String idempotencyKey, Object... uriVariables) {
        long started = System.nanoTime();
        AdyenResponse answer;
        try {
            answer = exchange(path, body, idempotencyKey, uriVariables);
        } catch (RuntimeException noAnswer) {
            capture(path, uriVariables, idempotencyKey, body, null, noAnswer.toString(), started);
            throw noAnswer;
        }
        capture(path, uriVariables, idempotencyKey, body, answer, null, started);
        return answer;
    }

    /** Saves one exchange as JSON (request body, Idempotency-Key, status, answer); headers, and so the API key, are not saved. */
    private void capture(String path, Object[] uriVariables, String idempotencyKey, Map<String, Object> body,
                         AdyenResponse answer, String error, long started) {
        if (captureDir == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("at", Instant.now().toString());
        record.put("path", uriVariables.length > 0 ? path.replace("{psp}", String.valueOf(uriVariables[0])) : path);
        record.put("idempotencyKey", idempotencyKey);
        record.put("ms", (System.nanoTime() - started) / 1_000_000);
        record.put("request", body);
        record.put("status", answer != null ? answer.status() : null);
        record.put("answer", answer != null ? answer.body() : null);
        record.put("error", error);
        String name = String.format("%d-%04d-%s.json", System.currentTimeMillis(), captureSeq.incrementAndGet(),
                path.replaceAll("[^a-z]+", "_").replaceAll("^_|_$", ""));
        try {
            Files.createDirectories(captureDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(captureDir.resolve(name).toFile(), record);
        } catch (IOException e) {
            log.warn("could not save the Adyen capture {}: {}", name, e.toString());
        }
    }

    private AdyenResponse exchange(String path, Map<String, Object> body, String idempotencyKey, Object... uriVariables) {
        return restClient.post()
                .uri(path, uriVariables)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    JsonNode json;
                    try {
                        json = response.bodyTo(JsonNode.class);
                    } catch (RuntimeException notJson) {
                        json = null;
                    }
                    return new AdyenResponse(response.getStatusCode().value(), json);
                });
    }
}
