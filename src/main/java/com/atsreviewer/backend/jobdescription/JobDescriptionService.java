package com.atsreviewer.backend.jobdescription;

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

import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.CreateRequest;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.Response;

@Service
public class JobDescriptionService {
	private static final int MIN_TEXT_LENGTH = 200;
	private static final int MAX_TEXT_LENGTH = 10_000;
	private static final int MAX_TITLE_LENGTH = 255;

	private final JdbcClient jdbcClient;

	public JobDescriptionService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	public Response create(UUID userId, UUID guestSessionId, CreateRequest request) {
		validateOwner(userId, guestSessionId);
		validateRequest(request);

		UUID id = UUID.randomUUID();
		Instant createdAt = Instant.now();
		if (userId != null) {
			jdbcClient.sql("""
					INSERT INTO job_descriptions (id, user_id, title, raw_text, created_at)
					VALUES (:id, :ownerId, :title, :rawText, :createdAt)
					""")
				.param("id", id)
				.param("ownerId", userId)
				.param("title", request.title().trim())
				.param("rawText", request.rawText())
				.param("createdAt", Timestamp.from(createdAt))
				.update();
		} else {
			jdbcClient.sql("""
					INSERT INTO job_descriptions (id, guest_session_id, title, raw_text, created_at)
					VALUES (:id, :ownerId, :title, :rawText, :createdAt)
					""")
				.param("id", id)
				.param("ownerId", guestSessionId)
				.param("title", request.title().trim())
				.param("rawText", request.rawText())
				.param("createdAt", Timestamp.from(createdAt))
				.update();
		}
		return new Response(id, request.title().trim(), request.rawText(), createdAt);
	}

	public List<Response> list(UUID userId, UUID guestSessionId) {
		validateOwner(userId, guestSessionId);
		if (userId != null) {
			return jdbcClient.sql("""
					SELECT id, title, raw_text, created_at
					FROM job_descriptions
					WHERE user_id = :ownerId AND is_template = FALSE
					ORDER BY created_at DESC, id
					""")
				.param("ownerId", userId)
				.query(this::mapResponse)
				.list();
		}
		return jdbcClient.sql("""
				SELECT id, title, raw_text, created_at
				FROM job_descriptions
				WHERE guest_session_id = :ownerId AND is_template = FALSE
				ORDER BY created_at DESC, id
				""")
			.param("ownerId", guestSessionId)
			.query(this::mapResponse)
			.list();
	}

	public Response get(UUID userId, UUID guestSessionId, UUID id) {
		validateOwner(userId, guestSessionId);
		Optional<Response> result;
		if (userId != null) {
			result = jdbcClient.sql("""
					SELECT id, title, raw_text, created_at
					FROM job_descriptions
					WHERE id = :id AND user_id = :ownerId AND is_template = FALSE
					""")
				.param("id", id)
				.param("ownerId", userId)
				.query(this::mapResponse)
				.optional();
		} else {
			result = jdbcClient.sql("""
					SELECT id, title, raw_text, created_at
					FROM job_descriptions
					WHERE id = :id AND guest_session_id = :ownerId AND is_template = FALSE
					""")
				.param("id", id)
				.param("ownerId", guestSessionId)
				.query(this::mapResponse)
				.optional();
		}
		return result.orElseThrow(() -> notFound());
	}

	@Transactional
	public void delete(UUID userId, UUID guestSessionId, UUID id) {
		validateOwner(userId, guestSessionId);
		int deleted;
		if (userId != null) {
			deleted = jdbcClient.sql("""
					DELETE FROM job_descriptions
					WHERE id = :id AND user_id = :ownerId AND is_template = FALSE
					""")
				.param("id", id)
				.param("ownerId", userId)
				.update();
		} else {
			deleted = jdbcClient.sql("""
					DELETE FROM job_descriptions
					WHERE id = :id AND guest_session_id = :ownerId AND is_template = FALSE
					""")
				.param("id", id)
				.param("ownerId", guestSessionId)
				.update();
		}
		if (deleted == 0) {
			throw notFound();
		}
	}

	private Response mapResponse(ResultSet resultSet, int rowNumber) throws SQLException {
		return new Response(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("title"),
				resultSet.getString("raw_text"),
				resultSet.getTimestamp("created_at").toInstant());
	}

	private void validateOwner(UUID userId, UUID guestSessionId) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new JobDescriptionException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to access job descriptions.");
		}
	}

	private void validateRequest(CreateRequest request) {
		if (request == null || request.title() == null || request.title().isBlank()) {
			throw new JobDescriptionException(HttpStatus.BAD_REQUEST, "TITLE_REQUIRED",
					"A job description title is required.");
		}
		if (characterLength(request.title().trim()) > MAX_TITLE_LENGTH) {
			throw new JobDescriptionException(HttpStatus.BAD_REQUEST, "TITLE_TOO_LONG",
					"The job description title must be 255 characters or fewer.");
		}
		if (request.rawText() == null || request.rawText().isBlank()) {
			throw new JobDescriptionException(HttpStatus.BAD_REQUEST, "JOB_DESCRIPTION_REQUIRED",
					"Job description text is required.");
		}
		int textLength = characterLength(request.rawText());
		if (textLength < MIN_TEXT_LENGTH) {
			throw new JobDescriptionException(HttpStatus.BAD_REQUEST, "JOB_DESCRIPTION_TOO_SHORT",
					"Job description text must be at least 200 characters.");
		}
		if (textLength > MAX_TEXT_LENGTH) {
			throw new JobDescriptionException(HttpStatus.BAD_REQUEST, "JOB_DESCRIPTION_TOO_LONG",
					"Job description text must be 10,000 characters or fewer.");
		}
	}

	private int characterLength(String value) {
		return value.codePointCount(0, value.length());
	}

	private JobDescriptionException notFound() {
		return new JobDescriptionException(HttpStatus.NOT_FOUND, "JOB_DESCRIPTION_NOT_FOUND",
				"The job description was not found.");
	}
}
