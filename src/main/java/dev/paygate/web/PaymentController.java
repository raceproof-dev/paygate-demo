package dev.paygate.web;

import dev.paygate.payment.PaymentService;
import dev.paygate.payment.PaymentStatus;
import dev.paygate.payment.PaymentView;
import dev.paygate.payment.StartPaymentRequest;
import dev.paygate.payment.StripePaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService service;
    private final StripePaymentService stripe;

    public PaymentController(PaymentService service, StripePaymentService stripe) {
        this.service = service;
        this.stripe = stripe;
    }

    /** 200 with the status (and the 3DS action to render), or 502 when the PSP gave no definitive answer (FAILED). */
    @PostMapping("/payments")
    public ResponseEntity<PaymentView> start(@Valid @RequestBody StartPaymentRequest request) {
        PaymentView view = "stripe".equals(request.psp()) ? stripe.start(request) : service.start(request);
        return ResponseEntity.status(view.status() == PaymentStatus.FAILED ? HttpStatus.BAD_GATEWAY : HttpStatus.OK).body(view);
    }

    @GetMapping("/payments/{id}")
    public PaymentView get(@PathVariable UUID id) {
        return service.view(id);
    }
}
