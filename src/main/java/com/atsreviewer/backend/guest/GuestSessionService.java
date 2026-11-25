package com.atsreviewer.backend.guest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.resume.ResumeFileDeletionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GuestSessionService {
	public static final String COOKIE_NAME = "guest_session";

	private final JdbcClient jdbcClient;
	private final ResumeFileDeletionService resumeFileDeletionService;
	private final Duration guestTtl;
	private final boolean captchaEnabled;
	private final String turnstileSecret;
	private final String ipHashSalt;
	private final SecretKey signingKey;
	private final JsonMapper jsonMapper;

	public GuestSessionService(
			JdbcClient jdbcClient,
			ResumeFileDeletionService resumeFileDeletionService,
			@Value("${app.guest.ttl}") Duration guestTtl,
			@Value("${app.guest.captcha-enabled:false}") boolean captchaEnabled,
			@Value("${app.guest.turnstile-secret:}") String turnstileSecret,
			@Value("${app.guest.ip-hash-salt:}") String ipHashSalt,
			SecretKey jwtSecretKey,
			JsonMapper jsonMapper) {
		this.jdbcClient = jdbcClient;
		this.resumeFileDeletionService = resumeFileDeletionService;
		this.guestTtl = guestTtl;
		this.captchaEnabled = captchaEnabled;
		this.turnstileSecret = turnstileSecret;
		this.ipHashSalt = ipHashSalt;
		this.signingKey = jwtSecretKey;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public GuestRecord create(String captchaToken, String clientIp) {
		if (captchaToken == null || captchaToken.isBlank()) {
			throw new GuestSessionException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED",
					"A valid CAPTCHA token is required.");
		}
		if (captchaEnabled && !verifyTurnstile(captchaToken, clientIp)) {
			throw new GuestSessionException(HttpStatus.BAD_REQUEST, "INVALID_CAPTCHA",
					"The CAPTCHA token is invalid or expired.");
		}

		UUID guestSessionId = UUID.randomUUID();
		Instant now = Instant.now();
		Instant expiresAt = now.plus(guestTtl);
		jdbcClient.sql("""
				INSERT INTO guest_sessions (id, ip_hash, created_at, expires_at)
				VALUES (:id, :ipHash, :createdAt, :expiresAt)
				""")
			.param("id", guestSessionId)
			.param("ipHash", hashIp(clientIp))
			.param("createdAt", Timestamp.from(now))
			.param("expiresAt", Timestamp.from(expiresAt))
			.update();

		return new GuestRecord(guestSessionId, expiresAt, sign(guestSessionId));
	}

	public Optional<UUID> resolve(String signedCookie) {
		if (signedCookie == null || signedCookie.isBlank()) {
			return Optional.empty();
		}
		int separator = signedCookie.lastIndexOf('.');
		if (separator <= 0 || separator == signedCookie.length() - 1) {
			return Optional.empty();
		}
		UUID guestSessionId;
		try {
			guestSessionId = UUID.fromString(signedCookie.substring(0, separator));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		if (!MessageDigest.isEqual(sign(guestSessionId).getBytes(StandardCharsets.UTF_8),
				signedCookie.getBytes(StandardCharsets.UTF_8))) {
			return Optional.empty();
		}

		return jdbcClient.sql("""
				SELECT id FROM guest_sessions
				WHERE id = :id AND expires_at > :now
				""")
			.param("id", guestSessionId)
			.param("now", Timestamp.from(Instant.now()))
			.query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
			.optional();
	}

	@Transactional
	public void purgeExpiredGuests() {
		Timestamp now = Timestamp.from(Instant.now());
		jdbcClient.sql("""
				SELECT id FROM guest_sessions
				WHERE expires_at <= :now
				""")
			.param("now", now)
			.query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
			.list()
			.forEach(resumeFileDeletionService::deleteForGuest);

		jdbcClient.sql("""
				DELETE FROM guest_sessions
				WHERE expires_at <= :now
				""")
			.param("now", now)
			.update();
	}

	@Transactional
	public void claim(UUID guestSessionId, UUID userId) {
		int exists = jdbcClient.sql("""
				SELECT COUNT(*) FROM guest_sessions
				WHERE id = :id AND expires_at > :now
				""")
			.param("id", guestSessionId)
			.param("now", Timestamp.from(Instant.now()))
			.query((resultSet, rowNumber) -> resultSet.getInt(1))
			.single();
		if (exists != 1) {
			throw new GuestSessionException(HttpStatus.UNAUTHORIZED, "INVALID_GUEST_SESSION",
					"The guest session is invalid or has expired.");
		}

		jdbcClient.sql("""
				UPDATE resumes
				SET user_id = :userId, guest_session_id = NULL
				WHERE guest_session_id = :guestSessionId
				""")
			.param("userId", userId)
			.param("guestSessionId", guestSessionId)
			.update();
		jdbcClient.sql("""
				UPDATE job_descriptions
				SET user_id = :userId, guest_session_id = NULL
				WHERE guest_session_id = :guestSessionId
				""")
			.param("userId", userId)
			.param("guestSessionId", guestSessionId)
			.update();
		jdbcClient.sql("""
				UPDATE consent_records
				SET user_id = :userId, guest_session_id = NULL
				WHERE guest_session_id = :guestSessionId
				""")
			.param("userId", userId)
			.param("guestSessionId", guestSessionId)
			.update();
		jdbcClient.sql("""
				DELETE FROM guest_sessions WHERE id = :guestSessionId
				""")
			.param("guestSessionId", guestSessionId)
			.update();
	}

	public Duration ttl() {
		return guestTtl;
	}

	private String sign(UUID guestSessionId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(signingKey);
			byte[] signature = mac.doFinal(guestSessionId.toString().getBytes(StandardCharsets.UTF_8));
			return guestSessionId + "." + Base64.getUrlEncoder().withoutPadding()
					.encodeToString(signature);
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Guest session signing is unavailable.", exception);
		}
	}

	private String hashIp(String clientIp) {
		String input = ipHashSalt + "|" + (clientIp == null ? "" : clientIp.trim());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private boolean verifyTurnstile(String captchaToken, String clientIp) {
		if (turnstileSecret == null || turnstileSecret.isBlank()) {
			throw new GuestSessionException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_NOT_CONFIGURED",
					"Guest sessions are temporarily unavailable.");
		}
		try {
			String form = "secret=" + URLEncoder.encode(turnstileSecret, StandardCharsets.UTF_8)
					+ "&response=" + URLEncoder.encode(captchaToken, StandardCharsets.UTF_8)
					+ "&remoteip=" + URLEncoder.encode(clientIp == null ? "" : clientIp, StandardCharsets.UTF_8);
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(form))
					.timeout(Duration.ofSeconds(5))
					.build();
			HttpResponse<String> response = HttpClient.newHttpClient()
					.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return false;
			}
			JsonNode body = jsonMapper.readTree(response.body());
			return body.path("success").booleanValue();
		} catch (Exception exception) {
			throw new GuestSessionException(HttpStatus.BAD_GATEWAY, "CAPTCHA_PROVIDER_UNAVAILABLE",
					"The CAPTCHA provider could not be reached.");
		}
	}

	public record GuestRecord(UUID guestSessionId, Instant expiresAt, String signedCookie) {
	}
}
