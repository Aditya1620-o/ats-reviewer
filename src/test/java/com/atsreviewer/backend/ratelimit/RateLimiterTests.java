package com.atsreviewer.backend.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class RateLimiterTests {
	@Test
	void loginLimitsUseHashedIpAndEmailKeys() {
		List<String> keys = new ArrayList<>();
		RateLimiter rateLimiter = new RateLimiter(
				(key, configuration) -> {
					keys.add(key);
					return new RateLimitConsumption(true, Duration.ZERO);
				},
				"test-salt");

		rateLimiter.checkLogin("192.0.2.10", "Person@example.com");

		assertEquals(2, keys.size());
		assertTrue(keys.get(0).startsWith("rl:login:ip:"));
		assertTrue(keys.get(1).startsWith("rl:login:email:"));
		assertFalse(keys.toString().contains("192.0.2.10"));
		assertFalse(keys.toString().contains("Person@example.com"));
	}

	@Test
	void rejectedRequestReturnsRetryAfter() {
		RateLimiter rateLimiter = new RateLimiter(
				(key, configuration) -> new RateLimitConsumption(false, Duration.ofMillis(1_200)),
				"test-salt");

		RateLimitException exception = assertThrows(RateLimitException.class,
				() -> rateLimiter.checkUpload(UUID.randomUUID(), null));
		var response = new RateLimitExceptionHandler().handleRateLimitException(exception);

		assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
		assertEquals("2", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		assertEquals("RATE_LIMIT_EXCEEDED", response.getBody().error().code());
	}

	@Test
	void valkeyOutageFailsOpenForUploadsButClosedForLogin() {
		RateLimiter rateLimiter = new RateLimiter(
				(key, configuration) -> {
					throw new RateLimitStoreUnavailableException(new IllegalStateException("offline"));
				},
				"test-salt");

		rateLimiter.checkUpload(UUID.randomUUID(), null);

		RateLimitException exception = assertThrows(RateLimitException.class,
				() -> rateLimiter.checkLogin("192.0.2.10", "person@example.com"));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
		assertEquals("RATE_LIMIT_UNAVAILABLE", exception.code());
	}
}
