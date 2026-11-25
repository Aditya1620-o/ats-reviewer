package com.atsreviewer.backend.resume;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ResumeFileDeletionService {
	private final JdbcClient jdbcClient;
	private final S3ResumeFileStorage fileStorage;

	public ResumeFileDeletionService(JdbcClient jdbcClient, S3ResumeFileStorage fileStorage) {
		this.jdbcClient = jdbcClient;
		this.fileStorage = fileStorage;
	}

	public void deleteForUser(UUID userId) {
		deleteFiles("""
				SELECT rv.s3_key
				FROM resume_versions rv
				JOIN resumes r ON r.id = rv.resume_id
				WHERE r.user_id = :ownerId
				""", userId);
	}

	public void deleteForGuest(UUID guestSessionId) {
		deleteFiles("""
				SELECT rv.s3_key
				FROM resume_versions rv
				JOIN resumes r ON r.id = rv.resume_id
				WHERE r.guest_session_id = :ownerId
				""", guestSessionId);
	}

	private void deleteFiles(String query, UUID ownerId) {
		List<String> storageKeys = jdbcClient.sql(query)
				.param("ownerId", ownerId)
				.query((resultSet, rowNumber) -> resultSet.getString("s3_key"))
				.list();
		storageKeys.forEach(fileStorage::delete);
	}
}
