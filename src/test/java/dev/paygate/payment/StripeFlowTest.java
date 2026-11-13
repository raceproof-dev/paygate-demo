package dev.paygate.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.paygate.config.Fixes;
import dev.paygate.stripe.StripeClient;
import dev.paygate.stripe.StripeResponse;
import dev.paygate.stripe.StripeSignature;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** paygate's Stripe flow with a scripted Stripe client: the Stripe fixes, and the webhook signature. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paygate.challenge.watchdog-scan-interval-ms=3600000",
        "paygate.stripe.webhook-secret=" + StripeFlowTest.SECRET})
class StripeFlowTest {

    static final String SECRET = "whsec_flow_test_secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    StripeClient stripe;

    @Autowired
    MockMvc mvc;
    @Autowired
    StripePaymentService service;
    @Autowired
    PaymentRepository payments;
    @Autowired
    Fixes fixes;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void allFixesOn() {
        fixes.set(Fixes.S1, true);
    }

    // ------------------------------------------------------------------ S1: return and webhook race

    @Test
    void s1ReturnsAndWebhookTellTheMerchantOnce() throws Exception {
        UUID id = startWithChallenge();
        when(stripe.retrievePaymentIntent(pi(id))).thenReturn(ok(intent(id, "requires_capture", null)));
        mvc.perform(get("/stripe/return/" + id)).andExpect(status().isOk());
        mvc.perform(get("/stripe/return/" + id)).andExpect(status().isOk());
        mvc.perform(webhook(event("payment_intent.amount_capturable_updated", intent(id, "requires_capture", null))))
                .andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(told(id, "COMPLETED")).isEqualTo(1);
    }

    @Test
    void s1OffEveryReportIsToldAgain() throws Exception {
        fixes.set(Fixes.S1, false);
        UUID id = startWithChallenge();
        when(stripe.retrievePaymentIntent(pi(id))).thenReturn(ok(intent(id, "requires_capture", null)));
        mvc.perform(get("/stripe/return/" + id));
        mvc.perform(get("/stripe/return/" + id));
        assertThat(told(id, "COMPLETED")).isEqualTo(2);
    }


    @Test
    void aCancelThatLostToTheShopperIsASuccess() {
        UUID id = startWithChallenge();
        ObjectNode error = mapper.createObjectNode();
        error.putObject("error").put("code", "payment_intent_unexpected_state").set("payment_intent", intent(id, "succeeded", null));
        when(stripe.cancelPaymentIntent(eq(pi(id)), anyString())).thenReturn(new StripeResponse(400, error));
        service.onPendingTimeout(payments.findById(id).orElseThrow());
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ the webhook endpoint

    @Test
    void rejectsAWebhookWithAWrongSignature() throws Exception {
        byte[] body = mapper.writeValueAsBytes(event("payment_intent.succeeded", intent(UUID.randomUUID(), "succeeded", null)));
        long t = Instant.now().getEpochSecond();
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", "t=" + t + ",v1=" + StripeSignature.sign(body, "whsec_wrong", t)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private UUID startWithChallenge() {
        UUID id = UUID.randomUUID();
        when(stripe.createPaymentIntent(any(), eq(id + ":create"))).thenReturn(ok(intent(id, "requires_action", null)));
        try {
            mvc.perform(start(id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_CHALLENGE"))
                    .andExpect(jsonPath("$.action.type").value("redirect"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private MockHttpServletRequestBuilder start(UUID id) {
        return post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
                {"transactionId": "%s", "merchantReference": "test", "psp": "stripe",
                 "amount": {"value": 1000, "currency": "EUR"}}""".formatted(id));
    }

    private static String pi(UUID id) {
        return "pi_test_" + id.toString().replace("-", "").substring(0, 16);
    }

    private ObjectNode intent(UUID id, String status, JsonNode latestCharge) {
        ObjectNode pi = mapper.createObjectNode();
        pi.put("id", pi(id)).put("object", "payment_intent").put("status", status);
        pi.putObject("metadata").put("payment_id", id.toString());
        if ("requires_action".equals(status)) {
            pi.putObject("next_action").put("type", "redirect_to_url").putObject("redirect_to_url").put("url", "https://hooks.test/3ds");
        }
        if (latestCharge != null) {
            pi.set("latest_charge", latestCharge);
        }
        return pi;
    }

    private ObjectNode charge(String status) {
        return mapper.createObjectNode().put("id", "ch_test").put("object", "charge").put("status", status);
    }

    private ObjectNode event(String type, ObjectNode object) {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", "evt_" + UUID.randomUUID().toString().replace("-", "")).put("object", "event").put("type", type);
        event.putObject("data").set("object", object);
        return event;
    }

    private MockHttpServletRequestBuilder webhook(ObjectNode event) throws Exception {
        byte[] body = mapper.writeValueAsBytes(event);
        long t = Instant.now().getEpochSecond();
        return post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Stripe-Signature", "t=" + t + ",v1=" + StripeSignature.sign(body, SECRET, t));
    }

    private StripeResponse ok(JsonNode body) {
        return new StripeResponse(200, body);
    }

    private StripeResponse serverError() {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("error").put("type", "api_error").put("message", "An unknown error occurred");
        return new StripeResponse(500, body);
    }

    private PaymentStatus statusOf(UUID id) {
        return payments.findById(id).orElseThrow().status();
    }

    private int told(UUID id, String status) {
        return jdbc.sql("SELECT count(*) FROM merchant_notification WHERE payment_id = :id AND status = :s")
                .param("id", id).param("s", status).query(Integer.class).single();
    }

    private int cancels(UUID id) {
        return jdbc.sql("SELECT count(*) FROM psp_cancel WHERE payment_id = :id").param("id", id).query(Integer.class).single();
    }
}
