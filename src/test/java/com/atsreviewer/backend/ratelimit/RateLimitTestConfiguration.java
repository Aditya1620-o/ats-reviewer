package com.atsreviewer.backend.ratelimit;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class RateLimitTestConfiguration {
	@Bean
	@Primary
	RateLimitStore testRateLimitStore() {
		return (key, configuration) -> new RateLimitConsumption(true, Duration.ZERO);
	}
}
