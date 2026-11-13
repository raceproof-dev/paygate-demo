package dev.paygate.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paygate.adyen.AdyenClient;
import dev.paygate.adyen.AdyenResponse;
import dev.paygate.adyen.ThreeDsCodec;
import dev.paygate.config.Fixes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The method step: the ACS's notification, the browser's skip after 10 s, and the race between them.
 * Adyen is mocked; the lab's fake covers the real timing.
 */
@Testcontainers
@SpringBootTest(properties = {
        "paygate.challenge.watchdog-scan-interval-ms=3600000",
        "paygate.method.timeout=1h"})
class MethodSkipTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    AdyenClient adyen;

    @Autowired
    PaymentService service;
    @Autowired
    PaymentRepository payments;
    @Autowired
    RefusalMarkers markers;
    @Autowired
    Fixes fixes;
    @Autowired
    ThreeDsCodec codec;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void allFixesOn() {
        fixes.set(Fixes.C3, true);
    }

    @Test
    void theSkipSendsTheFingerprintWithCompIndNUnderItsOwnKey() throws Exception {
        Payment p = paymentInMethod();
        when(adyen.paymentDetails(any(), anyString())).thenReturn(challengeShopper(p));

        service.onMethodSkipped(p.threeDsTransId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(adyen).paymentDetails(body.capture(), eq(p.id() + ":fingerprint-skip"));
        @SuppressWarnings("unchecked")
        String fingerprint = ((Map<String, String>) body.getValue().get("details")).get("threeds2.fingerprint");
        assertThat(codec.decodeBase64Url(fingerprint).path("threeDSCompInd").asText()).isEqualTo("N");
        assertThat(status(p)).isEqualTo(PaymentStatus.PENDING_CHALLENGE);
    }

    @Test
    void aSkipAfterTheMethodStepCompletedIsIgnored() {
        Payment p = paymentInMethod();
        payments.startThreeDs(p.id(), PaymentStatus.PENDING_CHALLENGE, p.threeDsTransId(), "pd");

        service.onMethodSkipped(p.threeDsTransId());

        verifyNoInteractions(adyen);
        assertThat(status(p)).isEqualTo(PaymentStatus.PENDING_CHALLENGE);
    }

    // ------------------------------------------------------------------ helpers

    private Payment paymentInMethod() {
        UUID id = UUID.randomUUID();
        payments.insert(id, "ref-" + id, 1000, "EUR");
        payments.startThreeDs(id, PaymentStatus.PENDING_METHOD, "trans-" + id, "pd-" + id);
        return payments.findById(id).orElseThrow();
    }

    private PaymentStatus status(Payment p) {
        return payments.findById(p.id()).orElseThrow().status();
    }

    private List<String> finalNotifications(Payment p) {
        return jdbc.sql("""
                        SELECT status FROM merchant_notification
                        WHERE payment_id = :id AND status IN ('COMPLETED', 'REFUSED', 'FAILED') ORDER BY id""")
                .param("id", p.id()).query(String.class).list();
    }

    private AdyenResponse challengeShopper(Payment p) throws Exception {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("threeDSServerTransID", p.threeDsTransId());
        token.put("acsTransID", "acs-1");
        token.put("acsURL", "http://acs/challenge");
        String encoded = Base64.getEncoder().encodeToString(
                objectMapper.writeValueAsString(token).getBytes(StandardCharsets.UTF_8));
        return new AdyenResponse(200, objectMapper.readTree("{\"resultCode\":\"ChallengeShopper\",\"action\":{"
                + "\"type\":\"threeDS2\",\"subtype\":\"challenge\",\"paymentData\":\"pd2\",\"token\":\"" + encoded + "\"}}"));
    }

    private AdyenResponse stepAlreadySubmitted() throws Exception {
        return new AdyenResponse(422, objectMapper.readTree(
                "{\"status\":422,\"errorCode\":\"704\",\"message\":\"request already processed or in progress\"}"));
    }
}
