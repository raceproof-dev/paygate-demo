package dev.paygate.web;

import dev.paygate.config.Fixes;
import dev.paygate.payment.MerchantNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Switch fixes at runtime, so the lab can run the same scenario unfixed and fixed without a restart.
 * PUT /_demo/fixes {"c3": false} switches the C3 fix off. Disable with paygate.demo-endpoints=false.
 */
@RestController
@ConditionalOnProperty(name = "paygate.demo-endpoints", havingValue = "true")
public class DemoController {

    private final Fixes fixes;
    private final MerchantNotifier merchant;

    public DemoController(Fixes fixes, MerchantNotifier merchant) {
        this.fixes = fixes;
        this.merchant = merchant;
    }

    /** What the merchant was told about a payment, so the demo page can show a duplicate final notification. */
    @GetMapping("/_demo/payments/{id}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable UUID id) {
        return merchant.list(id);
    }

    @GetMapping("/_demo/fixes")
    public Map<String, Boolean> get() {
        return fixes.all();
    }

    @PutMapping("/_demo/fixes")
    public Map<String, Boolean> set(@RequestBody Map<String, Boolean> changes) {
        changes.forEach(fixes::set);
        return fixes.all();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unknownFix(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
