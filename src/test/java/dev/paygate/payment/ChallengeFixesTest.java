package dev.paygate.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paygate.adyen.AdyenClient;
import dev.paygate.adyen.AdyenResponse;
import dev.paygate.adyen.ThreeDsCodec;
import dev.paygate.config.Fixes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The challenge-step fixes, each with the fix on and off. Adyen is mocked; the lab's fake covers the real timing.
 */
@Testcontainers
@SpringBootTest(properties = {
        "paygate.challenge.watchdog-scan-interval-ms=3600000",   // the tests call the timeout path themselves
        "paygate.challenge.timeout=1h"})
class ChallengeFixesTest {

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

    // ------------------------------------------------------------------ happy path

    @Test
    void cresYAuthorisedCompletesThePayment() throws Exception {
        Payment p = paymentInChallenge();
        when(adyen.paymentDetails(any(), anyString())).thenReturn(authorised());

        service.onChallengeResult(cres(p, "Y"));

        assertThat(status(p)).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(finalNotifications(p)).containsExactly("COMPLETED");
        verify(adyen).paymentDetails(any(), org.mockito.ArgumentMatchers.eq(p.id() + ":challenge"));
    }

    // ------------------------------------------------------------------ C3: CRes and watchdog race

    @Test
    void c3Fixed_theLosersRejectionChangesNothing() throws Exception {
        Payment loadedByWatchdog = paymentInChallenge();             // the watchdog read it while still pending...
        payments.setStatus(loadedByWatchdog.id(), PaymentStatus.COMPLETED, "PSP1"); // ...the CRes path won meanwhile
        when(adyen.paymentDetails(any(), anyString())).thenReturn(stepAlreadySubmitted());

        service.onChallengeTimeout(loadedByWatchdog);

        assertThat(status(loadedByWatchdog)).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(markers.count(loadedByWatchdog.id())).isZero();
        assertThat(finalNotifications(loadedByWatchdog)).isEmpty();
    }

    @Test
    void c3Unfixed_theLosersRejectionTurnsACompletedPaymentIntoFailed() throws Exception {
        fixes.set(Fixes.C3, false);
        Payment loadedByWatchdog = paymentInChallenge();
        payments.setStatus(loadedByWatchdog.id(), PaymentStatus.COMPLETED, "PSP1");
        when(adyen.paymentDetails(any(), anyString())).thenReturn(stepAlreadySubmitted());

        service.onChallengeTimeout(loadedByWatchdog);

        assertThat(status(loadedByWatchdog)).isEqualTo(PaymentStatus.FAILED);
        assertThat(markers.count(loadedByWatchdog.id())).isEqualTo(1);
    }

    @Test
    void c3Fixed_aLoserInFlightGets409AndChangesNothing() throws Exception {
        Payment loadedByWatchdog = paymentInChallenge();
        payments.setStatus(loadedByWatchdog.id(), PaymentStatus.COMPLETED, "PSP1");
        when(adyen.paymentDetails(any(), anyString())).thenReturn(stepAlreadySubmitted(409));   // measured on the sandbox

        service.onChallengeTimeout(loadedByWatchdog);

        assertThat(status(loadedByWatchdog)).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(markers.count(loadedByWatchdog.id())).isZero();
    }

    @Test
    void c3Fixed_theWinnersAnswerReplayedToTheLoserIsNotToldTwice() throws Exception {
        Payment p = paymentInChallenge();
        when(adyen.paymentDetails(any(), anyString())).thenReturn(authorised());
        service.onChallengeResult(cres(p, "Y"));                       // the CRes wins
        service.onChallengeTimeout(p);                                 // the watchdog loaded p before; Adyen replays

        assertThat(status(p)).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(finalNotifications(p)).containsExactly("COMPLETED");
    }

    @Test
    void c3Unfixed_theReplayedAnswerIsToldTwice() throws Exception {
        fixes.set(Fixes.C3, false);
        Payment p = paymentInChallenge();
        when(adyen.paymentDetails(any(), anyString())).thenReturn(authorised());
        service.onChallengeResult(cres(p, "Y"));
        service.onChallengeTimeout(p);

        assertThat(finalNotifications(p)).containsExactly("COMPLETED", "COMPLETED");
    }

    @Test
    void c3Fixed_anErrorAnswerAfterThePaymentEndedDoesNotOverwriteIt() throws Exception {
        Payment loadedByWatchdog = paymentInChallenge();
        payments.setStatus(loadedByWatchdog.id(), PaymentStatus.REFUSED, "PSP1");
        when(adyen.paymentDetails(any(), anyString())).thenReturn(new AdyenResponse(200, objectMapper.readTree(
                "{\"resultCode\":\"Error\",\"refusalReason\":\"RReq not received from DS\",\"refusalReasonCode\":\"39\"}")));

        service.onChallengeTimeout(loadedByWatchdog);

        assertThat(status(loadedByWatchdog)).isEqualTo(PaymentStatus.REFUSED);
        assertThat(markers.count(loadedByWatchdog.id())).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private Payment paymentInChallenge() {
        UUID id = UUID.randomUUID();
        payments.insert(id, "ref-" + id, 1000, "EUR");
        payments.startThreeDs(id, PaymentStatus.PENDING_CHALLENGE, "trans-" + id, "pd-" + id);
        return payments.findById(id).orElseThrow();
    }

    private String cres(Payment p, String transStatus) {
        Map<String, Object> cres = new LinkedHashMap<>();
        cres.put("messageType", "CRes");
        cres.put("threeDSServerTransID", p.threeDsTransId());
        cres.put("transStatus", transStatus);
        return codec.encodeBase64Url(cres);
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

    private AdyenResponse authorised() throws Exception {
        return new AdyenResponse(200, objectMapper.readTree("{\"resultCode\":\"Authorised\",\"pspReference\":\"PSP1\"}"));
    }

    private AdyenResponse stepAlreadySubmitted() throws Exception {
        return stepAlreadySubmitted(422);
    }

    private AdyenResponse stepAlreadySubmitted(int status) throws Exception {
        return new AdyenResponse(status, objectMapper.readTree(
                "{\"status\":" + status + ",\"errorCode\":\"704\",\"message\":\"request already processed or in progress\"}"));
    }
}
