package dev.paygate.web;

import dev.paygate.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Where the issuer's ACS posts back during 3DS (form posts, as browsers send them). */
@RestController
public class ThreeDsController {

    private static final Logger log = LoggerFactory.getLogger(ThreeDsController.class);

    private final PaymentService service;

    public ThreeDsController(PaymentService service) {
        this.service = service;
    }

    /** 3DS method step: the ACS reports that it collected the browser data. */
    @PostMapping(path = "/3ds/method", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String method(@RequestParam("threeDSMethodData") String threeDsMethodData) {
        service.onMethodData(threeDsMethodData);
        return "ok";
    }

    /** Challenge step: the shopper's challenge result (CRes), or an error message (erro) from the ACS. */
    @PostMapping(path = "/3ds/challenge", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public String challenge(@RequestParam(name = "cres", required = false) String cres,
                            @RequestParam(name = "erro", required = false) String erro) {
        if (cres != null) {
            service.onChallengeResult(cres);
        } else {
            // Not handled yet: the shopper then waits for the watchdog (catalog: "issuer posts an error instead of a CRes").
            log.warn("ACS posted an error instead of a CRes: {}", erro);
        }
        return "<!doctype html><p>Thank you. You can close this window.</p>";
    }
}
