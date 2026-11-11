package io.github.eswar7116.streamguard.routes;

/**
 * Callback for registering StreamGuard routes in code, in the style of Spring MVC's
 * {@code WebMvcConfigurer}. Declare one or more as beans; their registrations are combined with
 * the {@code streamguard.routes.*} properties.
 *
 * <pre>{@code
 * @Bean
 * StreamGuardConfigurer streamGuardRoutes() {
 *     return routes -> routes
 *             .monitor("/api/**")
 *             .exclude("/api/health")
 *             .authRoutes("/api/auth/login");
 * }
 * }</pre>
 */
@FunctionalInterface
public interface StreamGuardConfigurer {

    void configureRoutes(RouteRegistry routes);
}
