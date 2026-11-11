package io.github.eswar7116.streamguard.demo;

import io.github.eswar7116.streamguard.routes.StreamGuardConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tells StreamGuard which routes to watch, the same way any host application would. */
@Configuration(proxyBeanMethods = false)
class StreamGuardRoutesConfig {

    @Bean
    StreamGuardConfigurer streamGuardRoutes() {
        // Everything is monitored by default; excluding instead of narrowing with monitor("/api/**")
        // keeps requests for unknown paths visible, which is what scanner detection looks at
        return routes -> routes
                .exclude("/favicon.ico")
                .authRoutes("/api/auth/login");
    }
}
