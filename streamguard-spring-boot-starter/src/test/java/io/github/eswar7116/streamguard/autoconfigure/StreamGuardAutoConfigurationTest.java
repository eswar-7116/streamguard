package io.github.eswar7116.streamguard.autoconfigure;

import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.routes.RouteMatcher;
import io.github.eswar7116.streamguard.routes.StreamGuardConfigurer;
import io.github.eswar7116.streamguard.web.StreamGuardApiController;
import io.github.eswar7116.streamguard.web.StreamGuardDashboardController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class StreamGuardAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StreamGuardAutoConfiguration.class));

    @Test
    void configuresMonitoringApiAndDashboard() {
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(WindowedAggregator.class)
                .hasSingleBean(FilterRegistrationBean.class)
                .hasSingleBean(StreamGuardScheduler.class)
                .hasSingleBean(StreamGuardApiController.class)
                .hasSingleBean(StreamGuardDashboardController.class));
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("streamguard.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WindowedAggregator.class));
    }

    @Test
    void canMonitorWithoutExposingTheApi() {
        runner.withPropertyValues("streamguard.web.enabled=false")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(WindowedAggregator.class)
                        .doesNotHaveBean(StreamGuardApiController.class)
                        .doesNotHaveBean(StreamGuardDashboardController.class));
    }

    @Test
    void doesNothingOutsideServletApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StreamGuardAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WindowedAggregator.class));
    }

    @Test
    void combinesPropertyAndConfigurerRoutes() {
        runner.withPropertyValues("streamguard.routes.exclude=/actuator/**")
                .withBean(StreamGuardConfigurer.class, () -> routes -> routes.authRoutes("/login"))
                .run(ctx -> {
                    RouteMatcher matcher = ctx.getBean(RouteMatcher.class);
                    assertThat(matcher.shouldMonitor("/actuator/health")).isFalse();
                    assertThat(matcher.isAuthRoute("/login")).isTrue();
                });
    }

    @Test
    void leavesTheApplicationsOwnClockAlone() {
        Clock appClock = Clock.systemDefaultZone();
        runner.withBean(Clock.class, () -> appClock)
                .run(ctx -> assertThat(ctx.getBean(Clock.class)).isSameAs(appClock));
    }

    @Test
    void failsFastOnMalformedBasePath() {
        runner.withPropertyValues("streamguard.web.base-path=streamguard/")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
