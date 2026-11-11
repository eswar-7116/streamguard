package io.github.eswar7116.streamguard;

import io.github.eswar7116.streamguard.routes.StreamGuardConfigurer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots a host application with the starter on the classpath, as a consumer would. */
@SpringBootTest(classes = StreamGuardIntegrationTest.HostApp.class,
        properties = "streamguard.routes.exclude=/internal/**")
@AutoConfigureMockMvc
class StreamGuardIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class HostApp {

        @Bean
        HostController hostController() {
            return new HostController();
        }

        // Code-registered routes combine with the properties above
        @Bean
        StreamGuardConfigurer routes() {
            return routes -> routes.authRoutes("/login");
        }
    }

    @RestController
    static class HostController {

        @GetMapping("/orders/{id}")
        Map<String, Object> order(@PathVariable("id") long id) {
            return Map.of("id", id);
        }

        @GetMapping("/internal/ping")
        String ping() {
            return "pong";
        }

        @PostMapping("/login")
        ResponseEntity<String> login(@RequestParam("user") String user) {
            return "ok".equals(user) ? ResponseEntity.ok("token") : ResponseEntity.status(401).body("denied");
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void recordsRequestsByRouteTemplate() throws Exception {
        mvc.perform(get("/orders/1")).andExpect(status().isOk());
        mvc.perform(get("/orders/2")).andExpect(status().isOk());

        mvc.perform(get("/streamguard/api/routes").param("route", "/orders/{id}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endpoint").value("/orders/{id}"))
                .andExpect(jsonPath("$[0].totalRequests").value(2));
        mvc.perform(get("/streamguard/api/routes"))
                .andExpect(jsonPath("$[?(@.endpoint == '/orders/1')]").doesNotExist());
    }

    @Test
    void groupsUnmatchedRequestsAsNotFound() throws Exception {
        mvc.perform(get("/missing/a")).andExpect(status().isNotFound());
        mvc.perform(get("/missing/b")).andExpect(status().isNotFound());

        mvc.perform(get("/streamguard/api/routes").param("route", "NOT_FOUND"))
                .andExpect(jsonPath("$[0].totalRequests").value(2));
    }

    @Test
    void skipsExcludedRoutesAndItsOwnPages() throws Exception {
        mvc.perform(get("/internal/ping")).andExpect(status().isOk());
        mvc.perform(get("/streamguard/")).andExpect(status().isOk());

        mvc.perform(get("/streamguard/api/routes"))
                .andExpect(jsonPath("$[?(@.endpoint == '/internal/ping')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.endpoint =~ /\\/streamguard.*/)]").doesNotExist());
    }

    @Test
    void flagsBruteForceOnRegisteredAuthRoute() throws Exception {
        for (int i = 0; i < 11; i++) {
            mvc.perform(post("/login").param("user", "attacker")).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/streamguard/api/security/threats"))
                .andExpect(jsonPath("$[0].type").value("BRUTE_FORCE"))
                .andExpect(jsonPath("$[0].endpoint").value("/login"))
                .andExpect(jsonPath("$[0].count").value(11));
    }

    @Test
    void servesDashboardWithTrailingSlash() throws Exception {
        mvc.perform(get("/streamguard")).andExpect(redirectedUrl("/streamguard/"));
        mvc.perform(get("/streamguard/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>StreamGuard</title>")));
    }

    @Test
    void exposesWindowSettings() throws Exception {
        mvc.perform(get("/streamguard/api/info"))
                .andExpect(jsonPath("$.evaluationWindowSeconds").value(15))
                .andExpect(jsonPath("$.securityWindowSeconds").value(60));
    }

    @Test
    void rejectsBadWindows() throws Exception {
        mvc.perform(get("/streamguard/api/routes").param("window", "1y")).andExpect(status().isBadRequest());
        mvc.perform(get("/streamguard/api/routes").param("window", "16m")).andExpect(status().isBadRequest());
        mvc.perform(get("/streamguard/api/security/suspicious-ips").param("window", "5m"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookUpdatesAreForbiddenByDefault() throws Exception {
        mvc.perform(put("/streamguard/api/alerts/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"http://internal.example/hook\"]"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/streamguard/api/alerts/webhooks")).andExpect(jsonPath("$").isEmpty());
    }
}
