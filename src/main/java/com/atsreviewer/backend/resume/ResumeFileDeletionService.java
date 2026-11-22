package com.atsreviewer.backend.resume;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ResumeFileDeletionService {
	private final JdbcClient jdbcClient;
	private final ResumeFileStorage fileStorage;

	public ResumeFileDeletionService(JdbcClient jdbcClient, ResumeFileStorage fileStorage) {
		this.jdbcClient = jdbcClient;
		this.fileStorage = fileStorage;
	}

	public void deleteForUser(UUID userId) {
		deleteFiles("""
				SELECT rv.storage_file_id
				FROM resume_versions rv
				JOIN resumes r ON r.id = rv.resume_id
				WHERE r.user_id = :ownerId
				""", userId);
	}

	public void deleteForGuest(UUID guestSessionId) {
		deleteFiles("""
				SELECT rv.storage_file_id
				FROM resume_versions rv
				JOIN resumes r ON r.id = rv.resume_id
				WHERE r.guest_session_id = :ownerId
				""", guestSessionId);
	}

	private void deleteFiles(String query, UUID ownerId) {
		List<String> fileIds = jdbcClient.sql(query)
				.param("ownerId", ownerId)
				.query((resultSet, rowNumber) -> resultSet.getString("storage_file_id"))
				.list();
		fileIds.forEach(fileStorage::delete);
	}
}
