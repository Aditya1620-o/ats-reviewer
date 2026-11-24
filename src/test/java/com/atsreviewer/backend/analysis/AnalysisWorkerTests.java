package com.atsreviewer.backend.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.atsreviewer.backend.engine.model.ScoreItem;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.Status;
import com.atsreviewer.backend.ratelimit.RateLimitTestConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(RateLimitTestConfiguration.class)
class AnalysisWorkerTests {
	@Autowired
	private AnalysisWorker worker;

	@Autowired
	private JdbcClient jdbcClient;

	@Test
	void claimsAndProcessesAQueuedAnalysis() {
		UUID userId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO users (id, email, password_hash, created_at)
				VALUES (:id, :email, 'hash', :createdAt)
				""")
			.param("id", userId)
			.param("email", "worker-" + userId + "@example.com")
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID resumeId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO resumes (id, user_id, title, created_at)
				VALUES (:id, :userId, 'Resume', :createdAt)
				""")
			.param("id", resumeId)
			.param("userId", userId)
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID resumeVersionId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, s3_key, sha256, mime, size_bytes, created_at)
				VALUES (:id, :resumeId, 1, 'unused.pdf', :sha, 'application/pdf', 10, :createdAt)
				""")
			.param("id", resumeVersionId)
			.param("resumeId", resumeId)
			.param("sha", "0".repeat(64))
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID jobId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO job_descriptions
					(id, user_id, title, raw_text, created_at)
				VALUES (:id, :userId, 'Job', :text, :createdAt)
				""")
			.param("id", jobId)
			.param("userId", userId)
			.param("text", "Java developer with experience in Spring.")
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID analysisId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO analyses
					(id, resume_version_id, jd_id, engine_version, status, created_at)
				VALUES (:id, :resumeId, :jobId, '1.0.0', 'queued', :createdAt)
				""")
			.param("id", analysisId)
			.param("resumeId", resumeVersionId)
			.param("jobId", jobId)
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();

		assertEquals(analysisId, worker.claimNext());

		String status = jdbcClient.sql("SELECT status FROM analyses WHERE id = :id")
				.param("id", analysisId)
				.query((resultSet, rowNumber) -> resultSet.getString("status"))
				.single();
		assertEquals("processing", status);
	}

	@Test
	void savesSuccessAndItems() {
		Fixture fixture = createFixture();
		int skillId = jdbcClient.sql("SELECT id FROM skills WHERE canonical_name = 'Java'")
				.query((resultSet, rowNumber) -> resultSet.getInt("id"))
				.single();
		ScoreResult result = new ScoreResult(
				100.0,
				List.of(new ScoreItem(skillId, "Java", 3.0, Status.USED, Section.EXPERIENCE, 3.0)),
				"1.0.0");

		worker.saveSuccess(fixture.analysisId(), result);

		assertEquals("done", jdbcClient.sql("SELECT status FROM analyses WHERE id = :id")
				.param("id", fixture.analysisId())
				.query((resultSet, rowNumber) -> resultSet.getString("status"))
				.single());
		Integer itemCount = jdbcClient.sql("SELECT COUNT(*) FROM analysis_items WHERE analysis_id = :id")
				.param("id", fixture.analysisId())
				.query((resultSet, rowNumber) -> resultSet.getInt(1))
				.single();
		assertEquals(1, itemCount);
		assertNotNull(jdbcClient.sql("SELECT finished_at FROM analyses WHERE id = :id")
				.param("id", fixture.analysisId())
				.query((resultSet, rowNumber) -> resultSet.getTimestamp("finished_at"))
				.single());
	}

	private Fixture createFixture() {
		UUID userId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO users (id, email, password_hash, created_at)
				VALUES (:id, :email, 'hash', :createdAt)
				""")
			.param("id", userId)
			.param("email", "worker-success-" + userId + "@example.com")
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID resumeId = UUID.randomUUID();
		jdbcClient.sql("INSERT INTO resumes (id, user_id, title, created_at) VALUES (:id, :userId, 'Resume', :createdAt)")
				.param("id", resumeId)
				.param("userId", userId)
				.param("createdAt", Timestamp.from(Instant.now()))
				.update();
		UUID resumeVersionId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, s3_key, sha256, mime, size_bytes, created_at)
				VALUES (:id, :resumeId, 1, 'unused.pdf', :sha, 'application/pdf', 10, :createdAt)
				""")
			.param("id", resumeVersionId)
			.param("resumeId", resumeId)
			.param("sha", "1".repeat(64))
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID jobId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO job_descriptions (id, user_id, title, raw_text, created_at)
				VALUES (:id, :userId, 'Job', 'Java developer', :createdAt)
				""")
			.param("id", jobId)
			.param("userId", userId)
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		UUID analysisId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO analyses
					(id, resume_version_id, jd_id, engine_version, status, created_at)
				VALUES (:id, :resumeId, :jobId, '1.0.0', 'processing', :createdAt)
				""")
			.param("id", analysisId)
			.param("resumeId", resumeVersionId)
			.param("jobId", jobId)
			.param("createdAt", Timestamp.from(Instant.now()))
			.update();
		return new Fixture(analysisId);
	}

	private record Fixture(UUID analysisId) {
	}
}
