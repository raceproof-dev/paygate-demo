package dev.paygate.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paygate.adyen.AdyenClient;
import dev.paygate.adyen.AdyenResponse;
import dev.paygate.adyen.WebhookSignature;
import dev.paygate.config.Fixes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The webhook endpoint and the reconciliation of PSP successes after paygate ended a payment itself (W1 and the reconciliation fixes). */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paygate.challenge.watchdog-scan-interval-ms=3600000",
        "paygate.adyen.webhook.username=hook-user",
        "paygate.adyen.webhook.password=hook-pass",
        "paygate.adyen.webhook.hmac-key=" + WebhookReconciliationTest.KEY})
class WebhookReconciliationTest {

    static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    AdyenClient adyen;

    @Autowired
    MockMvc mvc;
    @Autowired
    Reconciler reconciler;
    @Autowired
    PaymentRepository payments;
    @Autowired
    RefusalMarkers markers;
    @Autowired
    Fixes fixes;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void allFixesOn() {
        when(adyen.cancel(anyString(), any(), anyString()))
                .thenReturn(new AdyenResponse(201, mapper.createObjectNode().put("status", "received")));
    }

    // ------------------------------------------------------------------ the endpoint

    @Test
    void acceptsASignedWebhookWithTheRightCredentials() throws Exception {
        UUID id = payment();
        mvc.perform(webhook(id, "PSP-OK", true, "hook-user:hook-pass", KEY))
                .andExpect(status().isOk()).andExpect(content().string("[accepted]"));
        assertThat(events(id)).isEqualTo(1);
    }

    @Test
    void rejectsWrongCredentialsAndBadSignaturesWithoutRecordingAnything() throws Exception {
        UUID id = payment();
        mvc.perform(webhook(id, "PSP-A", true, "hook-user:wrong", KEY)).andExpect(status().isUnauthorized());
        mvc.perform(webhook(id, "PSP-B", true, null, KEY)).andExpect(status().isUnauthorized());
        mvc.perform(webhook(id, "PSP-C", true, "hook-user:hook-pass", "ff".repeat(32))).andExpect(status().isUnauthorized());
        assertThat(events(id)).isZero();
    }

    // ------------------------------------------------------------------ W1: definitive refusal

    @Test
    void w1_aSuccessAfterADefinitiveRefusalIsIgnored() throws Exception {
        UUID id = payment();
        payments.setStatus(id, PaymentStatus.REFUSED, "PSP-REFUSED");     // Adyen's own Refused: no marker

        deliver(id, "PSP-LATE", true);

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.REFUSED);
        verify(adyen, never()).cancel(anyString(), any(), anyString());
    }


    @Test
    void theMethodTimeoutDoesNothingOnceTheMethodStepMovedOn() {
        UUID id = paymentInMethodStep();
        payments.startThreeDs(id, PaymentStatus.PENDING_CHALLENGE, "t-" + id, "pd");

        assertThat(reconciler.endWithoutAnswer(id, PaymentStatus.REFUSED, PaymentStatus.PENDING_METHOD, "timeout")).isFalse();
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.PENDING_CHALLENGE);
        assertThat(markers.count(id)).isZero();
    }



    @Test
    void aRefusalWebhookIsOnlyRecorded() throws Exception {
        UUID id = paymentInMethodStep();
        reconciler.endWithoutAnswer(id, PaymentStatus.REFUSED, PaymentStatus.PENDING_METHOD, "timeout");

        deliver(id, "PSP-NO", false);

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.REFUSED);
        assertThat(markers.unreconciled(id)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private UUID payment() {
        UUID id = UUID.randomUUID();
        payments.insert(id, "test", 1000, "EUR");
        return id;
    }

    private UUID paymentInMethodStep() {
        UUID id = payment();
        payments.startThreeDs(id, PaymentStatus.PENDING_METHOD, "t-" + id, "pd");
        jdbc.sql("INSERT INTO merchant_notification (payment_id, status) VALUES (:id, 'PENDING_METHOD')").param("id", id).update();
        return id;
    }

    private void deliver(UUID id, String pspReference, boolean success) throws Exception {
        mvc.perform(webhook(id, pspReference, success, "hook-user:hook-pass", KEY)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.RequestBuilder webhook(UUID id, String pspReference, boolean success,
                                                                        String basicAuth, String key) throws Exception {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("amount").put("currency", "EUR").put("value", 1000);
        item.put("eventCode", "AUTHORISATION");
        item.put("merchantAccountCode", "TestMerchant");
        item.put("merchantReference", id.toString());
        item.put("pspReference", pspReference);
        item.put("success", String.valueOf(success));
        item.putObject("additionalData").put("hmacSignature", WebhookSignature.sign(item, key));
        ObjectNode body = mapper.createObjectNode().put("live", "false");
        body.putArray("notificationItems").addObject().set("NotificationRequestItem", item);

        var request = post("/webhooks/adyen").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (basicAuth != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }

    private PaymentStatus statusOf(UUID id) {
        return payments.findById(id).orElseThrow().status();
    }

    private int events(UUID id) {
        return jdbc.sql("SELECT count(*) FROM psp_event WHERE payment_id = :id").param("id", id).query(Integer.class).single();
    }

    private List<String> cancels(UUID id) {
        return jdbc.sql("SELECT status FROM psp_cancel WHERE payment_id = :id").param("id", id).query(String.class).list();
    }

    private List<String> merchantHeard(UUID id) {
        return jdbc.sql("SELECT status FROM merchant_notification WHERE payment_id = :id ORDER BY id")
                .param("id", id).query(String.class).list();
    }
}
