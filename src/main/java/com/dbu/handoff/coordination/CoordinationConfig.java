package com.dbu.handoff.coordination;

import com.dbu.handoff.coordination.memory.InMemoryMessageBuffer;
import com.dbu.handoff.coordination.memory.InMemoryRelayChannel;
import com.dbu.handoff.coordination.memory.InMemorySocketOwnership;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the coordination layer.
 *
 * <p>Defaults to the in-memory implementations, which are correct for a single
 * instance and need no infrastructure. Set {@code handoff.coordination.mode} to
 * {@code redis} once the Redis implementations exist and more than one instance
 * is running.
 */
@Configuration
@EnableConfigurationProperties(CoordinationProperties.class)
public class CoordinationConfig {

    private static final Logger log = LoggerFactory.getLogger(CoordinationConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "handoff.coordination.mode", havingValue = "memory",
            matchIfMissing = true)
    public MessageBuffer inMemoryMessageBuffer(CoordinationProperties properties, Clock clock) {
        warnIfMultiInstance();
        return new InMemoryMessageBuffer(properties, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "handoff.coordination.mode", havingValue = "memory",
            matchIfMissing = true)
    public SocketOwnership inMemorySocketOwnership(CoordinationProperties properties, Clock clock) {
        return new InMemorySocketOwnership(properties, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "handoff.coordination.mode", havingValue = "memory",
            matchIfMissing = true)
    public RelayChannel inMemoryRelayChannel() {
        return new InMemoryRelayChannel();
    }

    private void warnIfMultiInstance() {
        log.warn("coordination is running in memory — correct for a single instance only. "
                + "Set handoff.coordination.mode=redis before running more than one.");
    }
}
