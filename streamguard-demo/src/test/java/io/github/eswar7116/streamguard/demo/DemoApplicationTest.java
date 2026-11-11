package io.github.eswar7116.streamguard.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "demo.traffic.enabled=false")
@AutoConfigureMockMvc
class DemoApplicationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FaultInjector faults;

    @Test
    void shopRoutesAreMonitoredByTemplate() throws Exception {
        mvc.perform(get("/api/orders/7")).andExpect(status().isOk());
        mvc.perform(get("/api/orders/8")).andExpect(status().isOk());

        mvc.perform(get("/streamguard/api/routes").param("route", "/api/orders/{id}"))
                .andExpect(jsonPath("$[0].totalRequests").value(2));
    }

    @Test
    void loginRouteIsRegisteredForBruteForceDetection() throws Exception {
        for (int i = 0; i < 11; i++) {
            mvc.perform(post("/api/auth/login").param("user", "mallory")).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/streamguard/api/security/threats"))
                .andExpect(jsonPath("$[0].type").value("BRUTE_FORCE"))
                .andExpect(jsonPath("$[0].endpoint").value("/api/auth/login"));
    }

    @Test
    void probesForUnknownPathsAreDetectedAsScanning() throws Exception {
        for (int i = 0; i < 21; i++) {
            mvc.perform(get("/probe-" + i).header("X-Forwarded-For", "198.51.100.99")).andExpect(status().isNotFound());
        }

        mvc.perform(get("/streamguard/api/security/threats"))
                .andExpect(jsonPath("$[?(@.type == 'SCANNER' && @.ip == '198.51.100.99')].count").value(21));
    }

    @Test
    void injectedFaultsShowUpAsErrorsOnTheRoute() throws Exception {
        faults.fail("/api/products", 1.0, 60_000);
        mvc.perform(get("/api/products")).andExpect(status().isServiceUnavailable());

        mvc.perform(get("/streamguard/api/routes").param("route", "/api/products"))
                .andExpect(jsonPath("$[0].errorRate").value(100.0));
    }
}
