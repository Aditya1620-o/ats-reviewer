package com.atsreviewer.backend.jobdescription;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.CreateJobDescriptionRequest;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.JobDescriptionResponse;

@Service
public class JobDescriptionService {
	private final JdbcClient jdbcClient;
	private final JdbcTemplate jdbcTemplate;
	private final JobDescriptionSkillCatalog skillCatalog;

	public JobDescriptionService(
			JdbcClient jdbcClient,
			JdbcTemplate jdbcTemplate,
			JobDescriptionSkillCatalog skillCatalog) {
		this.jdbcClient = jdbcClient;
		this.jdbcTemplate = jdbcTemplate;
		this.skillCatalog = skillCatalog;
	}

	@Transactional
	public JobDescriptionResponse create(
			UUID userId,
			UUID guestSessionId,
			CreateJobDescriptionRequest request) {
		requireOwner(userId, guestSessionId);

		List<JdSkill> classifiedSkills = skillCatalog.classify(request.rawText());
		List<Integer> requiredSkillIds = classifiedSkills.stream()
				.filter(JdSkill::required)
				.map(JdSkill::skillId)
				.toList();
		List<Integer> preferredSkillIds = classifiedSkills.stream()
				.filter(skill -> !skill.required())
				.map(JdSkill::skillId)
				.toList();
		UUID id = UUID.randomUUID();
		Instant createdAt = Instant.now();
		String title = request.title().trim();
		jdbcTemplate.execute((Connection connection) -> {
			try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO job_descriptions
					(id, user_id, guest_session_id, title, raw_text, created_at,
					 required_skill_ids, preferred_skill_ids)
				VALUES
					(?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
				statement.setObject(1, id);
				statement.setObject(2, userId);
				statement.setObject(3, guestSessionId);
				statement.setString(4, title);
				statement.setString(5, request.rawText());
				statement.setTimestamp(6, Timestamp.from(createdAt));
				statement.setArray(7, connection.createArrayOf("integer", requiredSkillIds.toArray()));
				statement.setArray(8, connection.createArrayOf("integer", preferredSkillIds.toArray()));
				statement.executeUpdate();
				return null;
			}
		});

		return new JobDescriptionResponse(
				id, title, request.rawText(), createdAt, requiredSkillIds, preferredSkillIds);
	}

	public List<JobDescriptionResponse> findAll(UUID userId, UUID guestSessionId) {
		requireOwner(userId, guestSessionId);
		String ownerColumn = userId != null ? "user_id" : "guest_session_id";
		UUID ownerId = userId != null ? userId : guestSessionId;
		return jdbcClient.sql("""
				SELECT id, title, raw_text, created_at, required_skill_ids, preferred_skill_ids
				FROM job_descriptions
				WHERE %s = :ownerId AND is_template = FALSE
				ORDER BY created_at DESC, id
				""".formatted(ownerColumn))
			.param("ownerId", ownerId)
			.query(this::toResponse)
			.list();
	}

	public JobDescriptionResponse findById(UUID id, UUID userId, UUID guestSessionId) {
		requireOwner(userId, guestSessionId);
		String ownerColumn = userId != null ? "user_id" : "guest_session_id";
		UUID ownerId = userId != null ? userId : guestSessionId;
		return jdbcClient.sql("""
				SELECT id, title, raw_text, created_at, required_skill_ids, preferred_skill_ids
				FROM job_descriptions
				WHERE id = :id AND %s = :ownerId AND is_template = FALSE
				""".formatted(ownerColumn))
			.param("id", id)
			.param("ownerId", ownerId)
			.query(this::toResponse)
			.optional()
			.orElseThrow(this::notFound);
	}

	@Transactional
	public void delete(UUID id, UUID userId, UUID guestSessionId) {
		requireOwner(userId, guestSessionId);
		String ownerColumn = userId != null ? "user_id" : "guest_session_id";
		UUID ownerId = userId != null ? userId : guestSessionId;
		int deleted = jdbcClient.sql("""
				DELETE FROM job_descriptions
				WHERE id = :id AND %s = :ownerId AND is_template = FALSE
				""".formatted(ownerColumn))
			.param("id", id)
			.param("ownerId", ownerId)
			.update();
		if (deleted == 0) {
			throw notFound();
		}
	}

	private JobDescriptionResponse toResponse(ResultSet resultSet, int rowNumber) throws SQLException {
		return new JobDescriptionResponse(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("title"),
				resultSet.getString("raw_text"),
				resultSet.getTimestamp("created_at").toInstant(),
				readIntegerArray(resultSet, "required_skill_ids"),
				readIntegerArray(resultSet, "preferred_skill_ids"));
	}

	private List<Integer> readIntegerArray(ResultSet resultSet, String columnName) throws SQLException {
		Array sqlArray = resultSet.getArray(columnName);
		if (sqlArray == null) {
			return List.of();
		}
		try {
			Object[] values = (Object[]) sqlArray.getArray();
			return java.util.Arrays.stream(values)
					.map(value -> ((Number) value).intValue())
					.toList();
		} finally {
			sqlArray.free();
		}
	}

	private void requireOwner(UUID userId, UUID guestSessionId) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new JobDescriptionException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to manage job descriptions.");
		}
	}

	private JobDescriptionException notFound() {
		return new JobDescriptionException(HttpStatus.NOT_FOUND, "JOB_DESCRIPTION_NOT_FOUND",
				"The job description was not found.");
	}
}
