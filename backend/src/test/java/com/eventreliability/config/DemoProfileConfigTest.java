package com.eventreliability.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code demo} profile: confirms {@code application-demo.yml} binds onto
 * {@link ReliabilityProperties} (low pattern threshold, notifier on, example ownership rules), so a
 * typo can't silently break demo startup. Pure binding check — no Spring context or Kafka needed.
 */
class DemoProfileConfigTest {

    @Test
    void demoProfileBindsLowThresholdNotifierAndOwnership() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-demo", new ClassPathResource("application-demo.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));

        ReliabilityProperties props = binder.bind("reliability", ReliabilityProperties.class).get();

        assertThat(props.pattern().threshold()).isEqualTo(5L);
        assertThat(props.notifier().enabled()).isTrue();
        assertThat(props.ownership().defaultTeam()).isEqualTo("Unassigned");
        assertThat(props.ownership().rules()).extracting(ReliabilityProperties.Ownership.Rule::team)
                .contains("Payments", "Orders", "Lending");
    }
}
