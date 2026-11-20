package com.atsreviewer.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.auth.AuthDtos.LoginRequest;
import com.atsreviewer.backend.auth.AuthDtos.SignupRequest;
import com.atsreviewer.backend.auth.AuthDtos.TokenResponse;

@Service
public class AuthService {
	private static final String ISSUER = "ats-reviewer";
	private static final String INVALID_CREDENTIALS = "Invalid email or password.";
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final JdbcClient jdbcClient;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	public AuthService(
			JdbcClient jdbcClient,
			PasswordEncoder passwordEncoder,
			JwtEncoder jwtEncoder,
			@Value("${app.auth.access-token-ttl}") Duration accessTokenTtl,
			@Value("${app.auth.refresh-token-ttl}") Duration refreshTokenTtl) {
		this.jdbcClient = jdbcClient;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
		this.accessTokenTtl = accessTokenTtl;
		this.refreshTokenTtl = refreshTokenTtl;
	}

	@Transactional
	public TokenResponse signup(SignupRequest request) {
		validatePasswordBytes(request.password());
		UUID userId = UUID.randomUUID();
		Instant now = Instant.now();
		try {
			jdbcClient.sql("""
					INSERT INTO users (id, email, password_hash, role, created_at)
					VALUES (:id, :email, :passwordHash, 'user', :createdAt)
					""")
				.param("id", userId)
				.param("email", normalizeEmail(request.email()))
				.param("passwordHash", passwordEncoder.encode(request.password()))
				.param("createdAt", Timestamp.from(now))
				.update();
		} catch (DuplicateKeyException exception) {
			throw new AuthException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
					"An account with this email already exists.");
		}
		return issueTokens(userId, "user");
	}

	@Transactional
	public TokenResponse login(LoginRequest request) {
		Optional<UserAccount> account = jdbcClient.sql("""
				SELECT id, password_hash, role
				FROM users
				WHERE email = :email
				""")
			.param("email", normalizeEmail(request.email()))
			.query((resultSet, rowNumber) -> new UserAccount(
					resultSet.getObject("id", UUID.class),
					resultSet.getString("password_hash"),
					resultSet.getString("role")))
			.optional();

		if (account.isEmpty()
				|| account.get().passwordHash() == null
				|| request.password().getBytes(StandardCharsets.UTF_8).length > 72
				|| !passwordEncoder.matches(request.password(), account.get().passwordHash())) {
			throw new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", INVALID_CREDENTIALS);
		}
		return issueTokens(account.get().id(), account.get().role());
	}

	@Transactional
	public TokenResponse refresh(String rawRefreshToken) {
		String tokenHash = hashToken(rawRefreshToken);
		Instant now = Instant.now();
		Optional<UserAccount> account = jdbcClient.sql("""
				SELECT users.id, users.password_hash, users.role
				FROM refresh_tokens
				JOIN users ON users.id = refresh_tokens.user_id
				WHERE refresh_tokens.token_hash = :tokenHash
				  AND refresh_tokens.revoked_at IS NULL
				  AND refresh_tokens.expires_at > :now
				FOR UPDATE
				""")
			.param("tokenHash", tokenHash)
			.param("now", Timestamp.from(now))
			.query((resultSet, rowNumber) -> new UserAccount(
					resultSet.getObject("id", UUID.class),
					resultSet.getString("password_hash"),
					resultSet.getString("role")))
			.optional();

		if (account.isEmpty()) {
			throw invalidRefreshToken();
		}

		int revoked = jdbcClient.sql("""
				UPDATE refresh_tokens
				SET revoked_at = :now
				WHERE token_hash = :tokenHash AND revoked_at IS NULL
				""")
			.param("now", Timestamp.from(now))
			.param("tokenHash", tokenHash)
			.update();
		if (revoked != 1) {
			throw invalidRefreshToken();
		}
		return issueTokens(account.get().id(), account.get().role());
	}

	@Transactional
	public void logout(String rawRefreshToken) {
		jdbcClient.sql("""
				UPDATE refresh_tokens
				SET revoked_at = :now
				WHERE token_hash = :tokenHash AND revoked_at IS NULL
				""")
			.param("now", Timestamp.from(Instant.now()))
			.param("tokenHash", hashToken(rawRefreshToken))
			.update();
	}

	private TokenResponse issueTokens(UUID userId, String role) {
		Instant now = Instant.now();
		Instant accessExpiresAt = now.plus(accessTokenTtl);
		Instant refreshExpiresAt = now.plus(refreshTokenTtl);

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.issuedAt(now)
				.expiresAt(accessExpiresAt)
				.subject(userId.toString())
				.claim("role", role)
				.claim("token_use", "access")
				.build();
		String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		byte[] tokenBytes = new byte[32];
		SECURE_RANDOM.nextBytes(tokenBytes);
		String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
		jdbcClient.sql("""
				INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
				VALUES (:userId, :tokenHash, :expiresAt)
				""")
			.param("userId", userId)
			.param("tokenHash", hashToken(refreshToken))
			.param("expiresAt", Timestamp.from(refreshExpiresAt))
			.update();

		return new TokenResponse(accessToken, "Bearer", accessTokenTtl.toSeconds(),
				refreshToken, refreshTokenTtl.toSeconds());
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static void validatePasswordBytes(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
					"Password must be no more than 72 UTF-8 bytes.");
		}
	}

	private static String hashToken(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static AuthException invalidRefreshToken() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
				"The refresh token is invalid, expired, or has already been used.");
	}

	private record UserAccount(UUID id, String passwordHash, String role) {
	}
}