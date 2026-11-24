package com.atsreviewer.backend.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.engine.Scorer;
import com.atsreviewer.backend.engine.extract.ResumeParser;
import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.engine.model.ScoreItem;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.jobdescription.JobDescriptionSkillCatalog;

@Component
public class AnalysisWorker {
	private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisWorker.class);
	private static final int MAX_ATTEMPTS = 3;

	private final JdbcClient jdbcClient;
	private final ResumeParser resumeParser;
	private final JobDescriptionSkillCatalog skillCatalog;
	private final Path uploadDirectory;

	public AnalysisWorker(
			JdbcClient jdbcClient,
			ResumeParser resumeParser,
			JobDescriptionSkillCatalog skillCatalog,
			@Value("${app.upload.directory}") String uploadDirectory) {
		this.jdbcClient = jdbcClient;
		this.resumeParser = resumeParser;
		this.skillCatalog = skillCatalog;
		this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
	}

	@Scheduled(fixedDelay = 5000)
	public void processOne() {
		UUID analysisId = claimNext();
		if (analysisId == null) {
			return;
		}

		try {
			AnalysisInput input = loadInput(analysisId);
			List<JdSkill> jdSkills = skillCatalog.classify(input.jobDescriptionText());
			var resume = resumeParser.parse(input.mime(), readResume(input.storageKey()));
			ScoreResult result = new Scorer(skillCatalog.skillMatcher()).score(resume, jdSkills);
			saveSuccess(analysisId, result);
		} catch (Exception exception) {
			LOGGER.error("Analysis {} failed", analysisId, exception);
			saveFailure(analysisId, "PROCESSING_FAILED");
		}
	}

	@Transactional
	public UUID claimNext() {
		UUID analysisId = jdbcClient.sql("""
				SELECT id
				FROM analyses
				WHERE status = 'queued' AND attempts < :maxAttempts
				ORDER BY created_at, id
				LIMIT 1
				""")
				.param("maxAttempts", MAX_ATTEMPTS)
				.query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
				.optional()
				.orElse(null);
		if (analysisId == null) {
			return null;
		}

		int updated = jdbcClient.sql("""
				UPDATE analyses
				SET status = 'processing', attempts = attempts + 1, started_at = COALESCE(started_at, :now),
				    error_code = NULL
				WHERE id = :id AND status = 'queued'
				""")
				.param("id", analysisId)
				.param("now", Timestamp.from(Instant.now()))
				.update();
		return updated == 1 ? analysisId : null;
	}

	private AnalysisInput loadInput(UUID analysisId) {
		return jdbcClient.sql("""
				SELECT rv.s3_key, rv.mime,
				       jd.raw_text
				FROM analyses a
				JOIN resume_versions rv ON rv.id = a.resume_version_id
				JOIN job_descriptions jd ON jd.id = a.jd_id
				WHERE a.id = :id
				""")
			.param("id", analysisId)
			.query((resultSet, rowNumber) -> new AnalysisInput(
					resultSet.getString("s3_key"),
					resultSet.getString("mime"),
					resultSet.getString("raw_text")))
			.single();
	}

	private byte[] readResume(String storageKey) throws IOException {
		Path resumeFile = uploadDirectory.resolve(storageKey).normalize();
		if (!resumeFile.startsWith(uploadDirectory)) {
			throw new IOException("Resume storage key escaped the upload directory.");
		}
		return Files.readAllBytes(resumeFile);
	}

	@Transactional
	public void saveSuccess(UUID analysisId, ScoreResult result) {
		jdbcClient.sql("""
				UPDATE analyses
				SET status = 'done', score = :score, error_code = NULL, finished_at = :finishedAt
				WHERE id = :id
				""")
				.param("id", analysisId)
				.param("score", result.score())
				.param("finishedAt", Timestamp.from(Instant.now()))
				.update();

		jdbcClient.sql("DELETE FROM analysis_items WHERE analysis_id = :id")
				.param("id", analysisId)
				.update();
		for (ScoreItem item : result.items()) {
			jdbcClient.sql("""
					INSERT INTO analysis_items
						(analysis_id, skill_id, weight, status, found_in, points)
					VALUES
						(:analysisId, :skillId, :weight, :status,
						 :foundIn, :points)
					""")
					.param("analysisId", analysisId)
					.param("skillId", item.skillId())
					.param("weight", item.weight())
					.param("status", item.status().name().toLowerCase(java.util.Locale.ROOT))
					.param("foundIn",
							item.foundIn() == null
									? null
									: item.foundIn().name().toLowerCase(java.util.Locale.ROOT))
					.param("points", item.points())
					.update();
		}
	}

	@Transactional
	public void saveFailure(UUID analysisId, String errorCode) {
		int attempts = jdbcClient.sql("SELECT attempts FROM analyses WHERE id = :id")
				.param("id", analysisId)
				.query((resultSet, rowNumber) -> resultSet.getInt("attempts"))
				.single();
		boolean exhausted = attempts >= MAX_ATTEMPTS;
		jdbcClient.sql("""
				UPDATE analyses
				SET status = :status,
				    error_code = :errorCode,
				    finished_at = :finishedAt
				WHERE id = :id
				""")
				.param("id", analysisId)
				.param("status", exhausted ? "failed" : "queued")
				.param("errorCode", errorCode)
				.param("finishedAt", exhausted ? Timestamp.from(Instant.now()) : null)
				.update();
	}

	private record AnalysisInput(String storageKey, String mime, String jobDescriptionText) {
	}
}
