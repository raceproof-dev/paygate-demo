package dev.paygate.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownPaymentException extends RuntimeException {

    public UnknownPaymentException(String message) {
        super(message);
    }
}
