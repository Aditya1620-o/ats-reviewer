package com.atsreviewer.backend.analysis;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.analysis.AnalysisDtos.Item;
import com.atsreviewer.backend.analysis.SharedReportDtos.PublicReport;
import com.atsreviewer.backend.analysis.SharedReportDtos.ShareResponse;

@Service
public class SharedReportService {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final int TOKEN_BYTES = 16;

	private final JdbcClient jdbcClient;
	private final Duration reportTtl;

	public SharedReportService(
			JdbcClient jdbcClient,
			@Value("${app.shared-report.ttl:7d}") Duration reportTtl) {
		this.jdbcClient = jdbcClient;
		this.reportTtl = reportTtl;
	}

	@Transactional
	public ShareResponse create(UUID userId, UUID guestSessionId, UUID analysisId) {
		validateOwner(userId, guestSessionId);
		String ownerColumn = userId != null ? "r.user_id" : "r.guest_session_id";
		UUID ownerId = userId != null ? userId : guestSessionId;
		Optional<String> analysisStatus = jdbcClient.sql("""
				SELECT a.status
				FROM analyses a
				JOIN resume_versions rv ON rv.id = a.resume_version_id
				JOIN resumes r ON r.id = rv.resume_id
				JOIN job_descriptions jd ON jd.id = a.jd_id
				WHERE a.id = :analysisId
				  AND %s = :ownerId
				  AND jd.%s = :ownerId
				  AND jd.is_template = FALSE
				""".formatted(ownerColumn, userId != null ? "user_id" : "guest_session_id"))
			.param("analysisId", analysisId)
			.param("ownerId", ownerId)
			.query((resultSet, rowNumber) -> resultSet.getString("status"))
			.optional();
		if (analysisStatus.isEmpty()) {
			throw analysisNotFound();
		}
		if (!"done".equals(analysisStatus.get())) {
			throw new AnalysisException(HttpStatus.CONFLICT, "ANALYSIS_NOT_COMPLETE",
					"Only completed analyses can be shared.");
		}

		String token = generateToken();
		Instant expiresAt = Instant.now().plus(reportTtl);
		jdbcClient.sql("""
				INSERT INTO shared_reports (token, analysis_id, expires_at)
				VALUES (:token, :analysisId, :expiresAt)
				""")
			.param("token", token)
			.param("analysisId", analysisId)
			.param("expiresAt", Timestamp.from(expiresAt))
			.update();
		return new ShareResponse(token, expiresAt);
	}

	public PublicReport get(String token) {
		SharedAnalysis sharedAnalysis = jdbcClient.sql("""
				SELECT sr.token, sr.expires_at, a.id, a.status, a.engine_version,
				       a.score, a.created_at, a.started_at, a.finished_at
				FROM shared_reports sr
				JOIN analyses a ON a.id = sr.analysis_id
				WHERE sr.token = :token
				  AND sr.revoked_at IS NULL
				  AND sr.expires_at > :now
				  AND a.status = 'done'
				""")
			.param("token", token)
			.param("now", Timestamp.from(Instant.now()))
			.query(this::mapSharedAnalysis)
			.optional()
			.orElseThrow(this::sharedReportNotFound);
		List<Item> items = jdbcClient.sql("""
				SELECT ai.skill_id, s.canonical_name, ai.weight, ai.status, ai.found_in, ai.points
				FROM analysis_items ai
				JOIN skills s ON s.id = ai.skill_id
				WHERE ai.analysis_id = :analysisId
				ORDER BY ai.skill_id
				""")
			.param("analysisId", sharedAnalysis.analysis().id())
			.query((resultSet, rowNumber) -> new Item(
					resultSet.getInt("skill_id"),
					resultSet.getString("canonical_name"),
					resultSet.getBigDecimal("weight"),
					resultSet.getString("status"),
					resultSet.getString("found_in"),
					resultSet.getBigDecimal("points")))
			.list();
		AnalysisDtos.Response analysis = new AnalysisDtos.Response(
				sharedAnalysis.analysis().id(),
				sharedAnalysis.analysis().status(),
				sharedAnalysis.analysis().engineVersion(),
				sharedAnalysis.analysis().score(),
				null,
				sharedAnalysis.analysis().createdAt(),
				sharedAnalysis.analysis().startedAt(),
				sharedAnalysis.analysis().finishedAt(),
				items);
		return new PublicReport(sharedAnalysis.token(), sharedAnalysis.expiresAt(), analysis);
	}

	@Transactional
	public void revoke(UUID userId, UUID guestSessionId, String token) {
		validateOwner(userId, guestSessionId);
		String ownerColumn = userId != null ? "r.user_id" : "r.guest_session_id";
		String jdOwnerColumn = userId != null ? "jd.user_id" : "jd.guest_session_id";
		UUID ownerId = userId != null ? userId : guestSessionId;
		int updated = jdbcClient.sql("""
				UPDATE shared_reports sr
				SET revoked_at = COALESCE(sr.revoked_at, :now)
				WHERE sr.token = :token
				  AND EXISTS (
				      SELECT 1
				      FROM analyses a
				      JOIN resume_versions rv ON rv.id = a.resume_version_id
				      JOIN resumes r ON r.id = rv.resume_id
				      JOIN job_descriptions jd ON jd.id = a.jd_id
				      WHERE a.id = sr.analysis_id
				        AND %s = :ownerId
				        AND %s = :ownerId
				        AND jd.is_template = FALSE
				  )
				""".formatted(ownerColumn, jdOwnerColumn))
			.param("token", token)
			.param("ownerId", ownerId)
			.param("now", Timestamp.from(Instant.now()))
			.update();
		if (updated == 0) {
			throw sharedReportNotFound();
		}
	}

	private SharedAnalysis mapSharedAnalysis(ResultSet resultSet, int rowNumber) throws SQLException {
		AnalysisHeader header = new AnalysisHeader(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("status"),
				resultSet.getString("engine_version"),
				resultSet.getBigDecimal("score"),
				resultSet.getTimestamp("created_at").toInstant(),
				toInstant(resultSet.getTimestamp("started_at")),
				toInstant(resultSet.getTimestamp("finished_at")));
		return new SharedAnalysis(
				resultSet.getString("token"),
				resultSet.getTimestamp("expires_at").toInstant(),
				header);
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private String generateToken() {
		byte[] tokenBytes = new byte[TOKEN_BYTES];
		SECURE_RANDOM.nextBytes(tokenBytes);
		return TOKEN_ENCODER.encodeToString(tokenBytes);
	}

	private void validateOwner(UUID userId, UUID guestSessionId) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new AnalysisException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to manage shared reports.");
		}
	}

	private AnalysisException analysisNotFound() {
		return new AnalysisException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND",
				"The analysis was not found.");
	}

	private AnalysisException sharedReportNotFound() {
		return new AnalysisException(HttpStatus.NOT_FOUND, "SHARED_REPORT_NOT_FOUND",
				"The shared report was not found or is no longer available.");
	}

	private record AnalysisHeader(
			UUID id,
			String status,
			String engineVersion,
			BigDecimal score,
			Instant createdAt,
			Instant startedAt,
			Instant finishedAt) {
	}

	private record SharedAnalysis(String token, Instant expiresAt, AnalysisHeader analysis) {
	}
}
