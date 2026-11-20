package com.atsreviewer.backend.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentService {
	private final JdbcClient jdbcClient;
	private final String noticeVersion;
	private final String subjectPepper;

	public ConsentService(
			JdbcClient jdbcClient,
			@Value("${app.consent.notice-version}") String noticeVersion,
			@Value("${app.consent.subject-pepper:}") String subjectPepper) {
		this.jdbcClient = jdbcClient;
		this.noticeVersion = noticeVersion;
		this.subjectPepper = subjectPepper;
	}

	@Transactional
	public void giveConsent(UUID userId, UUID guestSessionId, boolean ageConfirmed18) {
		if (!ageConfirmed18) {
			throw new ConsentException(HttpStatus.UNPROCESSABLE_ENTITY, "AGE_CONFIRMATION_REQUIRED",
					"You must be at least 18 years old to use this service.");
		}
		if (userId == null && guestSessionId == null) {
			throw new ConsentException(HttpStatus.FORBIDDEN, "GUEST_SESSION_REQUIRED",
					"A guest session or authenticated account is required.");
		}
		String subjectHash = hashSubject(userId != null
				? "user:" + userId
				: "guest:" + guestSessionId);
		jdbcClient.sql("""
				INSERT INTO consent_records
					(id, user_id, guest_session_id, subject_hash, notice_version,
					 age_confirmed_18, given_at, withdrawn_at)
				VALUES
					(:id, :userId, :guestSessionId, :subjectHash, :noticeVersion,
					 TRUE, :givenAt, NULL)
				""")
			.param("id", UUID.randomUUID())
			.param("userId", userId)
			.param("guestSessionId", guestSessionId)
			.param("subjectHash", subjectHash)
			.param("noticeVersion", noticeVersion)
			.param("givenAt", Timestamp.from(Instant.now()))
			.update();
	}

	public boolean hasCurrentConsent(UUID userId, UUID guestSessionId) {
		if (userId == null && guestSessionId == null) {
			return false;
		}
		Integer count = jdbcClient.sql("""
				SELECT COUNT(*)
				FROM consent_records
				WHERE (:userId IS NULL OR user_id = :userId)
				  AND (:guestSessionId IS NULL OR guest_session_id = :guestSessionId)
				  AND notice_version = :noticeVersion
				  AND withdrawn_at IS NULL
				  AND age_confirmed_18 = TRUE
				""")
			.param("userId", userId)
			.param("guestSessionId", guestSessionId)
			.param("noticeVersion", noticeVersion)
			.query((resultSet, rowNumber) -> resultSet.getInt(1))
			.single();
		return count != null && count > 0;
	}

	@Transactional
	public void withdraw(UUID userId, UUID guestSessionId) {
		if (userId == null && guestSessionId == null) {
			throw new ConsentException(HttpStatus.FORBIDDEN, "SUBJECT_REQUIRED",
					"An authenticated account or guest session is required.");
		}
		Instant now = Instant.now();
		if (guestSessionId != null) {
			jdbcClient.sql("""
					UPDATE consent_records
					SET withdrawn_at = :now, guest_session_id = NULL
					WHERE guest_session_id = :guestSessionId AND withdrawn_at IS NULL
					""")
				.param("now", Timestamp.from(now))
				.param("guestSessionId", guestSessionId)
				.update();
			jdbcClient.sql("DELETE FROM job_descriptions WHERE guest_session_id = :id")
				.param("id", guestSessionId)
				.update();
			jdbcClient.sql("DELETE FROM resumes WHERE guest_session_id = :id")
				.param("id", guestSessionId)
				.update();
			jdbcClient.sql("DELETE FROM guest_sessions WHERE id = :id")
				.param("id", guestSessionId)
				.update();
			return;
		}

		jdbcClient.sql("""
				UPDATE consent_records
				SET withdrawn_at = :now, user_id = NULL
				WHERE user_id = :userId AND withdrawn_at IS NULL
				""")
			.param("now", Timestamp.from(now))
			.param("userId", userId)
			.update();
		jdbcClient.sql("DELETE FROM job_descriptions WHERE user_id = :id")
			.param("id", userId)
			.update();
		jdbcClient.sql("DELETE FROM resumes WHERE user_id = :id")
			.param("id", userId)
			.update();
	}

	public String noticeVersion() {
		return noticeVersion;
	}

	private String hashSubject(String subject) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((subjectPepper + "|" + subject).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}
}
