package com.atsreviewer.backend.analysis;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.analysis.AnalysisDtos.CreateRequest;
import com.atsreviewer.backend.analysis.AnalysisDtos.Item;
import com.atsreviewer.backend.analysis.AnalysisDtos.Response;
import com.atsreviewer.backend.engine.Scorer;

@Service
public class AnalysisService {
	private final JdbcClient jdbcClient;

	public AnalysisService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	public Response create(UUID userId, UUID guestSessionId, CreateRequest request) {
		validateOwner(userId, guestSessionId);
		if (request == null || request.resumeVersionId() == null) {
			throw new AnalysisException(HttpStatus.BAD_REQUEST, "RESUME_VERSION_REQUIRED",
					"A resume version ID is required.");
		}
		if (request.jobDescriptionId() == null) {
			throw new AnalysisException(HttpStatus.BAD_REQUEST, "JOB_DESCRIPTION_REQUIRED",
					"A job description ID is required.");
		}
		if (!inputsBelongToOwner(userId, guestSessionId, request)) {
			throw inputsNotFound();
		}

		Optional<UUID> existingId = findExistingId(request);
		if (existingId.isPresent()) {
			Response existing = loadOwnedAnalysis(userId, guestSessionId, existingId.get())
					.orElseThrow(() -> analysisNotFound());
			if ("failed".equals(existing.status())) {
				jdbcClient.sql("""
						UPDATE analyses
						SET status = 'queued', attempts = 0, error_code = NULL,
						    started_at = NULL, finished_at = NULL
						WHERE id = :id AND status = 'failed'
						""")
					.param("id", existing.id())
					.update();
				return loadOwnedAnalysis(userId, guestSessionId, existing.id())
						.orElseThrow(() -> analysisNotFound());
			}
			return existing;
		}

		UUID analysisId = UUID.randomUUID();
		Instant createdAt = Instant.now();
		jdbcClient.sql("""
				INSERT INTO analyses
					(id, resume_version_id, jd_id, engine_version, status, created_at)
				VALUES (:id, :resumeVersionId, :jobDescriptionId, :engineVersion, 'queued', :createdAt)
				""")
			.param("id", analysisId)
			.param("resumeVersionId", request.resumeVersionId())
			.param("jobDescriptionId", request.jobDescriptionId())
			.param("engineVersion", Scorer.ENGINE_VERSION)
			.param("createdAt", Timestamp.from(createdAt))
			.update();

		return loadOwnedAnalysis(userId, guestSessionId, analysisId)
				.orElseThrow(() -> analysisNotFound());
	}

	public Response get(UUID userId, UUID guestSessionId, UUID analysisId) {
		validateOwner(userId, guestSessionId);
		return loadOwnedAnalysis(userId, guestSessionId, analysisId)
				.orElseThrow(() -> analysisNotFound());
	}

	private boolean inputsBelongToOwner(UUID userId, UUID guestSessionId, CreateRequest request) {
		String sql;
		if (userId != null) {
			sql = """
					SELECT COUNT(*)
					FROM resume_versions rv
					JOIN resumes r ON r.id = rv.resume_id
					JOIN job_descriptions jd ON jd.id = :jobDescriptionId
					WHERE rv.id = :resumeVersionId
					  AND r.user_id = :ownerId
					  AND jd.user_id = :ownerId
					  AND jd.is_template = FALSE
					""";
		} else {
			sql = """
					SELECT COUNT(*)
					FROM resume_versions rv
					JOIN resumes r ON r.id = rv.resume_id
					JOIN job_descriptions jd ON jd.id = :jobDescriptionId
					WHERE rv.id = :resumeVersionId
					  AND r.guest_session_id = :ownerId
					  AND jd.guest_session_id = :ownerId
					  AND jd.is_template = FALSE
					""";
		}
		int matches = jdbcClient.sql(sql)
				.param("resumeVersionId", request.resumeVersionId())
				.param("jobDescriptionId", request.jobDescriptionId())
				.param("ownerId", userId != null ? userId : guestSessionId)
				.query((resultSet, rowNumber) -> resultSet.getInt(1))
				.single();
		return matches == 1;
	}

	private Optional<UUID> findExistingId(CreateRequest request) {
		return jdbcClient.sql("""
				SELECT id
				FROM analyses
				WHERE resume_version_id = :resumeVersionId
				  AND jd_id = :jobDescriptionId
				  AND engine_version = :engineVersion
				""")
			.param("resumeVersionId", request.resumeVersionId())
			.param("jobDescriptionId", request.jobDescriptionId())
			.param("engineVersion", Scorer.ENGINE_VERSION)
			.query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
			.optional();
	}

	private Optional<Response> loadOwnedAnalysis(UUID userId, UUID guestSessionId, UUID analysisId) {
		String sql;
		if (userId != null) {
			sql = """
					SELECT a.id, a.status, a.engine_version, a.score, a.error_code,
					       a.created_at, a.started_at, a.finished_at
					FROM analyses a
					JOIN resume_versions rv ON rv.id = a.resume_version_id
					JOIN resumes r ON r.id = rv.resume_id
					JOIN job_descriptions jd ON jd.id = a.jd_id
					WHERE a.id = :analysisId
					  AND r.user_id = :ownerId
					  AND jd.user_id = :ownerId
					  AND jd.is_template = FALSE
					""";
		} else {
			sql = """
					SELECT a.id, a.status, a.engine_version, a.score, a.error_code,
					       a.created_at, a.started_at, a.finished_at
					FROM analyses a
					JOIN resume_versions rv ON rv.id = a.resume_version_id
					JOIN resumes r ON r.id = rv.resume_id
					JOIN job_descriptions jd ON jd.id = a.jd_id
					WHERE a.id = :analysisId
					  AND r.guest_session_id = :ownerId
					  AND jd.guest_session_id = :ownerId
					  AND jd.is_template = FALSE
					""";
		}

		Optional<AnalysisHeader> header = jdbcClient.sql(sql)
				.param("analysisId", analysisId)
				.param("ownerId", userId != null ? userId : guestSessionId)
				.query(this::mapHeader)
				.optional();
		return header.map(this::withItems);
	}

	private AnalysisHeader mapHeader(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AnalysisHeader(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("status"),
				resultSet.getString("engine_version"),
				resultSet.getBigDecimal("score"),
				resultSet.getString("error_code"),
				resultSet.getTimestamp("created_at").toInstant(),
				toInstant(resultSet.getTimestamp("started_at")),
				toInstant(resultSet.getTimestamp("finished_at")));
	}

	private Response withItems(AnalysisHeader header) {
		List<Item> items = jdbcClient.sql("""
				SELECT ai.skill_id, s.canonical_name, ai.weight, ai.status, ai.found_in, ai.points
				FROM analysis_items ai
				JOIN skills s ON s.id = ai.skill_id
				WHERE ai.analysis_id = :analysisId
				ORDER BY ai.skill_id
				""")
			.param("analysisId", header.id())
			.query(this::mapItem)
			.list();
		return new Response(
				header.id(), header.status(), header.engineVersion(), header.score(), header.errorCode(),
				header.createdAt(), header.startedAt(), header.finishedAt(), items);
	}

	private Item mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
		return new Item(
				resultSet.getInt("skill_id"),
				resultSet.getString("canonical_name"),
				resultSet.getBigDecimal("weight"),
				resultSet.getString("status"),
				resultSet.getString("found_in"),
				resultSet.getBigDecimal("points"));
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private void validateOwner(UUID userId, UUID guestSessionId) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new AnalysisException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to access analyses.");
		}
	}

	private AnalysisException inputsNotFound() {
		return new AnalysisException(HttpStatus.NOT_FOUND, "ANALYSIS_INPUT_NOT_FOUND",
				"The resume version or job description was not found.");
	}

	private AnalysisException analysisNotFound() {
		return new AnalysisException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND",
				"The analysis was not found.");
	}

	private record AnalysisHeader(
			UUID id,
			String status,
			String engineVersion,
			BigDecimal score,
			String errorCode,
			Instant createdAt,
			Instant startedAt,
			Instant finishedAt) {
	}
}
