package dev.paygate.payment;

import dev.paygate.config.PaygateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Ends 3DS steps that never finished. A challenge the shopper abandoned: paygate submits transStatus U to Adyen.
 * A method step the issuer never reported: paygate refuses the payment itself.
 */
@Component
public class ChallengeWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ChallengeWatchdog.class);

    private final PaymentRepository payments;
    private final PaymentService service;
    private final StripePaymentService stripe;
    private final PaygateProperties properties;

    public ChallengeWatchdog(PaymentRepository payments, PaymentService service, StripePaymentService stripe,
                             PaygateProperties properties) {
        this.payments = payments;
        this.service = service;
        this.stripe = stripe;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${paygate.challenge.watchdog-scan-interval-ms}")
    public void scan() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.challenge().timeout());
        for (Payment payment : payments.findChallengeTimedOut(PaymentRepository.PSP_ADYEN, cutoff)) {
            if (!payments.claimForWatchdog(payment.id())) {
                continue;
            }
            log.info("challenge of payment {} timed out: submitting transStatus U", payment.id());
            try {
                service.onChallengeTimeout(payment);
            } catch (RuntimeException e) {
                log.error("watchdog could not end payment {}", payment.id(), e);
            }
        }
    }

    /** Stripe: no outcome within the pending timeout. paygate refuses the payment and cancels the PaymentIntent. */
    @Scheduled(fixedDelayString = "${paygate.challenge.watchdog-scan-interval-ms}")
    public void scanStripe() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.stripe().pendingTimeout());
        for (Payment payment : payments.findChallengeTimedOut(PaymentRepository.PSP_STRIPE, cutoff)) {
            if (!payments.claimForWatchdog(payment.id())) {
                continue;
            }
            try {
                stripe.onPendingTimeout(payment);
            } catch (RuntimeException e) {
                log.error("watchdog could not end Stripe payment {}", payment.id(), e);
            }
        }
    }

    /** Ends method steps the issuer never finished: paygate refuses the payment itself (no Adyen call). */
    @Scheduled(fixedDelayString = "${paygate.challenge.watchdog-scan-interval-ms}")
    public void scanMethods() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.method().timeout());
        for (Payment payment : payments.findMethodTimedOut(cutoff)) {
            try {
                service.onMethodTimeout(payment);
            } catch (RuntimeException e) {
                log.error("watchdog could not end payment {}", payment.id(), e);
            }
        }
    }
}
