package dev.paygate.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live fix switches. Each one turns on the fix for one edge case; off shows the bug.
 * The ids match the lab's scenario ids (C3, S1, ...).
 */
@Component
public class Fixes {

    public static final String C3 = "c3";
    /** Stripe: the return and the webhook race; only the first final outcome is recorded and told to the merchant. */
    public static final String S1 = "s1";

    private final Map<String, Boolean> switches = new ConcurrentHashMap<>();

    public Fixes(PaygateProperties properties) {
        switches.put(C3, properties.fixes().c3());
        switches.put(S1, properties.fixes().s1());
    }

    public boolean on(String id) {
        Boolean value = switches.get(id);
        if (value == null) {
            throw new IllegalArgumentException("unknown fix: " + id);
        }
        return value;
    }

    public void set(String id, boolean value) {
        if (!switches.containsKey(id)) {
            throw new IllegalArgumentException("unknown fix: " + id + "; known: " + switches.keySet());
        }
        switches.put(id, value);
    }

    public Map<String, Boolean> all() {
        return new TreeMap<>(switches);
    }
}
