package io.github.eswar7116.streamguard.autoconfigure;

import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.alerting.AlertPublisher;
import io.github.eswar7116.streamguard.availability.AvailabilityStateMachine;
import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.detection.AnomalyDetector;
import io.github.eswar7116.streamguard.detection.AnomalyLog;
import io.github.eswar7116.streamguard.detection.WindowEvaluator;
import io.github.eswar7116.streamguard.filter.RequestMetricsFilter;
import io.github.eswar7116.streamguard.forecast.TrafficForecaster;
import io.github.eswar7116.streamguard.routes.RouteMatcher;
import io.github.eswar7116.streamguard.routes.RouteRegistry;
import io.github.eswar7116.streamguard.routes.StreamGuardConfigurer;
import io.github.eswar7116.streamguard.security.SecurityAnalyzer;
import io.github.eswar7116.streamguard.web.StreamGuardApiController;
import io.github.eswar7116.streamguard.web.StreamGuardDashboardController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.DispatcherServlet;

import java.time.Clock;

/**
 * Auto-configures StreamGuard in a Spring MVC (servlet) application. Disable it entirely with
 * {@code streamguard.enabled=false}, or just the API and dashboard with {@code streamguard.web.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "streamguard", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StreamGuardProperties.class)
public class StreamGuardAutoConfiguration {

    // Internal on purpose: registering a Clock bean would collide with the application's own
    private final Clock clock = Clock.systemUTC();

    @Bean
    @ConditionalOnMissingBean
    public RouteMatcher streamGuardRouteMatcher(StreamGuardProperties properties,
                                                ObjectProvider<StreamGuardConfigurer> configurers) {
        StreamGuardProperties.Routes routes = properties.routes();
        RouteRegistry registry = new RouteRegistry()
                .monitor(routes.include().toArray(String[]::new))
                .exclude(routes.exclude().toArray(String[]::new))
                .authRoutes(routes.auth().toArray(String[]::new));
        configurers.orderedStream().forEach(c -> c.configureRoutes(registry));
        return new RouteMatcher(validBasePath(properties), registry);
    }

    @Bean
    public WindowedAggregator streamGuardAggregator(StreamGuardProperties properties) {
        return new WindowedAggregator(properties, clock);
    }

    @Bean
    public AnomalyLog streamGuardAnomalyLog() {
        return new AnomalyLog();
    }

    @Bean
    public AvailabilityStateMachine streamGuardAvailability() {
        return new AvailabilityStateMachine(clock);
    }

    @Bean
    public TrafficForecaster streamGuardForecaster(StreamGuardProperties properties) {
        return new TrafficForecaster(properties);
    }

    @Bean
    public AlertPublisher streamGuardAlertPublisher(StreamGuardProperties properties,
                                                    ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new AlertPublisher(properties.alert().webhookUrls(), restClientBuilder.getIfAvailable(RestClient::builder));
    }

    @Bean
    public SecurityAnalyzer streamGuardSecurityAnalyzer(WindowedAggregator aggregator, RouteMatcher routes) {
        return new SecurityAnalyzer(aggregator, clock, routes::isAuthRoute);
    }

    @Bean
    public WindowEvaluator streamGuardWindowEvaluator(WindowedAggregator aggregator, AnomalyLog anomalyLog,
                                                      AlertPublisher alertPublisher,
                                                      AvailabilityStateMachine availability,
                                                      TrafficForecaster forecaster, StreamGuardProperties properties) {
        return new WindowEvaluator(aggregator, new AnomalyDetector(), anomalyLog, alertPublisher, availability,
                forecaster, clock, properties);
    }

    @Bean
    public StreamGuardScheduler streamGuardScheduler(WindowEvaluator evaluator, WindowedAggregator aggregator,
                                                     SecurityAnalyzer securityAnalyzer) {
        return new StreamGuardScheduler()
                .every(1_000, "window evaluation", evaluator::tick)
                .every(60_000, "idle window eviction", aggregator::evictIdleWindows)
                .every(60_000, "scanner state eviction", securityAnalyzer::evictStaleEndpointSets);
    }

    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> streamGuardFilter(WindowedAggregator aggregator,
                                                                          RouteMatcher routes,
                                                                          StreamGuardProperties properties) {
        FilterRegistrationBean<RequestMetricsFilter> registration = new FilterRegistrationBean<>(
                new RequestMetricsFilter(aggregator, routes, clock, properties.filter().trustForwardedFor()));
        registration.setName("streamGuardFilter");
        registration.setOrder(RequestMetricsFilter.ORDER);
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "streamguard.web", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class WebConfiguration {

        @Bean
        public StreamGuardApiController streamGuardApiController(WindowedAggregator aggregator, AnomalyLog anomalyLog,
                                                                 AvailabilityStateMachine availability,
                                                                 SecurityAnalyzer securityAnalyzer,
                                                                 TrafficForecaster forecaster,
                                                                 AlertPublisher alertPublisher,
                                                                 StreamGuardProperties properties) {
            return new StreamGuardApiController(aggregator, anomalyLog, availability, securityAnalyzer, forecaster,
                    alertPublisher, properties);
        }

        @Bean
        public StreamGuardDashboardController streamGuardDashboardController(StreamGuardProperties properties) {
            return new StreamGuardDashboardController(validBasePath(properties));
        }
    }

    // Controllers map the raw property, so it must already be in canonical form
    static String validBasePath(StreamGuardProperties properties) {
        String basePath = properties.web().basePath();
        if (basePath == null || !basePath.startsWith("/") || basePath.length() < 2 || basePath.endsWith("/")) {
            throw new IllegalStateException("streamguard.web.base-path must start with '/', not end with '/' "
                    + "and not be '/' (got '" + basePath + "')");
        }
        return basePath;
    }
}
