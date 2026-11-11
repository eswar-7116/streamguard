package io.github.eswar7116.streamguard.demo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** The demo's business API. Handlers sleep a little to look like real work. */
@RestController
@RequestMapping("/api")
class ShopController {

    private final FaultInjector faults;

    ShopController(FaultInjector faults) {
        this.faults = faults;
    }

    @GetMapping("/products")
    List<Map<String, Object>> products() {
        work("/api/products", 10, 60);
        return List.of(Map.of("id", 1, "name", "Keyboard"), Map.of("id", 2, "name", "Monitor"));
    }

    @GetMapping("/products/{id}")
    Map<String, Object> product(@PathVariable("id") long id) {
        work("/api/products/{id}", 5, 40);
        if (id > 1000) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such product");
        }
        return Map.of("id", id, "name", "Product " + id);
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> order(@PathVariable("id") long id) {
        work("/api/orders/{id}", 5, 40);
        return Map.of("id", id, "status", "SHIPPED");
    }

    @PostMapping("/orders")
    ResponseEntity<Map<String, Object>> placeOrder() {
        work("/api/orders", 20, 90);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", ThreadLocalRandom.current().nextInt(10_000)));
    }

    @PostMapping("/payments")
    Map<String, Object> pay() {
        // Payments are slower and occasionally time out at the gateway
        work("/api/payments", 40, 180);
        if (ThreadLocalRandom.current().nextInt(20) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment gateway timeout");
        }
        return Map.of("status", "charged");
    }

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, Object>> login(@RequestParam(name = "user", defaultValue = "demo") String user) {
        work("/api/auth/login", 10, 50);
        return "demo".equals(user)
                ? ResponseEntity.ok(Map.of("token", "demo-token"))
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid credentials"));
    }

    private void work(String route, int minMs, int maxMs) {
        faults.maybeFail(route);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
