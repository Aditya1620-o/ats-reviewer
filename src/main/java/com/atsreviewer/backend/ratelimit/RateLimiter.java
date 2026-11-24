package com.atsreviewer.backend.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import io.github.bucket4j.BucketConfiguration;

@Service
public class RateLimiter {
	private static final Logger LOGGER = LoggerFactory.getLogger(RateLimiter.class);

	private static final BucketConfiguration USER_ANALYSIS = configuration(10, Duration.ofHours(1));
	private static final BucketConfiguration GUEST_ANALYSIS = configuration(3, Duration.ofHours(1));
	private static final BucketConfiguration GUEST_IP_ANALYSIS = configuration(20, Duration.ofDays(1));
	private static final BucketConfiguration UPLOAD = configuration(20, Duration.ofDays(1));
	private static final BucketConfiguration LOGIN = configuration(5, Duration.ofMinutes(15));

	private final RateLimitStore store;
	private final String ipHashSalt;

	public RateLimiter(
			RateLimitStore store,
			@Value("${app.guest.ip-hash-salt:}") String ipHashSalt) {
		this.store = store;
		this.ipHashSalt = ipHashSalt;
	}

	public void checkLogin(String clientIp, String email) {
		check("rl:login:ip:" + hash("ip", clientIp), LOGIN, true);
		check("rl:login:email:" + hash("email", normalizeEmail(email)), LOGIN, true);
	}

	public void checkAnalysis(UUID userId, UUID guestSessionId, String clientIp) {
		if (userId != null) {
			check("rl:analysis:user:" + userId, USER_ANALYSIS, false);
			return;
		}
		check("rl:analysis:guest:" + guestSessionId, GUEST_ANALYSIS, false);
		check("rl:analysis:guest-ip:" + hash("ip", clientIp), GUEST_IP_ANALYSIS, false);
	}

	public void checkUpload(UUID userId, UUID guestSessionId) {
		String subjectId = userId != null ? userId.toString() : guestSessionId.toString();
		String subjectType = userId != null ? "user" : "guest";
		check("rl:upload:" + subjectType + ":" + subjectId, UPLOAD, false);
	}

	private void check(String key, BucketConfiguration configuration, boolean failClosed) {
		RateLimitConsumption consumption;
		try {
			consumption = store.consume(key, configuration);
		} catch (RateLimitStoreUnavailableException exception) {
			LOGGER.error("Valkey unavailable while checking rate limit for {}", key, exception);
			if (!failClosed) {
				return;
			}
			throw new RateLimitException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE",
					"Login is temporarily unavailable.", null);
		}
		if (!consumption.consumed()) {
			throw new RateLimitException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
					"Too many requests. Try again later.", consumption.retryAfter());
		}
	}

	private String hash(String namespace, String value) {
		String input = ipHashSalt + "|" + namespace + "|" + (value == null ? "" : value.trim());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static BucketConfiguration configuration(long capacity, Duration refillPeriod) {
		return BucketConfiguration.builder()
				.addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, refillPeriod))
				.build();
	}

	private static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}
}
