package io.github.eswar7116.streamguard.demo;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ThreadLocalRandom;

/** Makes one route fail for a while, so the demo can show an error spike on demand. */
@Component
public class FaultInjector {

    private record Fault(String route, double failureRate, long untilMs) {
    }

    private volatile Fault fault;

    public void fail(String route, double failureRate, long durationMs) {
        fault = new Fault(route, failureRate, System.currentTimeMillis() + durationMs);
    }

    void maybeFail(String route) {
        Fault f = fault;
        if (f != null && f.route().equals(route) && System.currentTimeMillis() < f.untilMs()
                && ThreadLocalRandom.current().nextDouble() < f.failureRate()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "injected fault");
        }
    }
}
