package com.atsreviewer.backend.resume;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.poi.ooxml.POIXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import com.atsreviewer.backend.consent.ConsentService;
import com.atsreviewer.backend.engine.extract.ResumeParser;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeSummary;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeUploadResponse;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeVersionSummary;

@Service
public class ResumeUploadService {
	public static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;
	private static final Logger logger = LoggerFactory.getLogger(ResumeUploadService.class);

	private final JdbcClient jdbcClient;
	private final ResumeParser resumeParser;
	private final ResumeFileStorage fileStorage;
	private final ResumeFileDeletionService resumeFileDeletionService;
	private final ConsentService consentService;

	public ResumeUploadService(
			JdbcClient jdbcClient,
			ResumeParser resumeParser,
			ResumeFileStorage fileStorage,
			ResumeFileDeletionService resumeFileDeletionService,
			ConsentService consentService) {
		this.jdbcClient = jdbcClient;
		this.resumeParser = resumeParser;
		this.fileStorage = fileStorage;
		this.resumeFileDeletionService = resumeFileDeletionService;
		this.consentService = consentService;
	}

	@Transactional
	public ResumeUploadResponse upload(UUID userId, UUID guestSessionId, MultipartFile file) {
		return upload(userId, guestSessionId, null, file);
	}

	@Transactional
	public ResumeUploadResponse upload(
			UUID userId,
			UUID guestSessionId,
			UUID targetResumeId,
			MultipartFile file) {
		validateOwner(userId, guestSessionId);
		if (!consentService.hasCurrentConsent(userId, guestSessionId)) {
			throw new ResumeUploadException(HttpStatus.FORBIDDEN, "CONSENT_REQUIRED",
					"Accept the privacy notice and confirm you are at least 18 years old before uploading.");
		}
		if (file == null || file.isEmpty()) {
			throw new ResumeUploadException(HttpStatus.BAD_REQUEST, "EMPTY_FILE",
					"Choose a non-empty PDF or DOCX file.");
		}
		if (file.getSize() > MAX_FILE_SIZE_BYTES) {
			throw tooLarge();
		}

		String originalFilename = file.getOriginalFilename();
		if (originalFilename == null || originalFilename.isBlank()) {
			throw unsupportedFile();
		}
		String filename = Path.of(originalFilename.replace('\\', '/')).getFileName().toString();
		String lowerFilename = filename.toLowerCase(Locale.ROOT);
		String contentType = file.getContentType();
		boolean isPdf = lowerFilename.endsWith(".pdf")
				&& ResumeParser.PDF_MIME_TYPE.equals(contentType);
		boolean isDocx = lowerFilename.endsWith(".docx")
				&& ResumeParser.DOCX_MIME_TYPE.equals(contentType);
		if (!isPdf && !isDocx) {
			throw unsupportedFile();
		}

		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException exception) {
			throw new ResumeUploadException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED",
					"The uploaded file could not be read.");
		}
		if (bytes.length > MAX_FILE_SIZE_BYTES) {
			throw tooLarge();
		}
		if (isPdf && !hasPdfSignature(bytes)) {
			throw unsupportedFile();
		}
		if (isDocx && !hasZipSignature(bytes)) {
			throw unsupportedFile();
		}

		try {
			resumeParser.parse(contentType, bytes);
		} catch (IOException | IllegalArgumentException | POIXMLException exception) {
			throw new ResumeUploadException(HttpStatus.BAD_REQUEST, "INVALID_FILE",
					"The file is not a valid, readable PDF or DOCX document.");
		}

		String fileHash = sha256(bytes);
		UUID resumeId;
		int versionNo;
		boolean createNewResume = targetResumeId == null;
		if (createNewResume) {
			resumeId = UUID.randomUUID();
			versionNo = 1;
		} else {
			resumeId = lockOwnedResume(targetResumeId, userId, guestSessionId)
					.orElseThrow(this::resumeNotFound);
			Optional<Integer> duplicateVersion = findVersionByHash(resumeId, fileHash);
			if (duplicateVersion.isPresent()) {
				throw new ResumeUploadException(HttpStatus.CONFLICT, "DUPLICATE_VERSION",
						"This file matches version " + duplicateVersion.get() + ".");
			}
			versionNo = nextVersionNumber(resumeId);
		}

		UUID versionId = UUID.randomUUID();
		Instant createdAt = Instant.now();
		String extension = isPdf ? ".pdf" : ".docx";
		String objectName = resumeId + "/" + versionId + extension;
		String fileId;
		try {
			fileId = fileStorage.store(objectName, bytes, contentType);
		} catch (RestClientException exception) {
			throw new ResumeUploadException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE",
					"The resume storage service is unavailable.", exception);
		}

		try {
			if (createNewResume) {
				insertResume(resumeId, userId, guestSessionId, filename, createdAt);
			}
			insertVersion(versionId, resumeId, versionNo, fileId, fileHash, contentType, bytes.length, createdAt);
		} catch (DataAccessException exception) {
			try {
				fileStorage.delete(fileId);
			} catch (RestClientException cleanupException) {
				logger.error("Could not remove ImageKit file {} after its database insert failed", fileId,
						cleanupException);
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}

		return new ResumeUploadResponse(
				resumeId, versionId, versionNo, filename, contentType, bytes.length, createdAt);
	}

	public List<ResumeSummary> findAll(UUID userId, UUID guestSessionId) {
		validateOwner(userId, guestSessionId);
		String sql = userId != null
				? """
						SELECT r.id AS resume_id, r.title, r.created_at AS resume_created_at,
						       rv.id AS version_id, rv.version_no, rv.mime, rv.size_bytes,
						       rv.extracted_text, rv.sections, rv.parse_warnings,
						       rv.created_at AS version_created_at
						FROM resumes r
						LEFT JOIN resume_versions rv ON rv.resume_id = r.id
						WHERE r.user_id = :ownerId
						ORDER BY r.created_at DESC, rv.version_no DESC
						"""
				: """
						SELECT r.id AS resume_id, r.title, r.created_at AS resume_created_at,
						       rv.id AS version_id, rv.version_no, rv.mime, rv.size_bytes,
						       rv.extracted_text, rv.sections, rv.parse_warnings,
						       rv.created_at AS version_created_at
						FROM resumes r
						LEFT JOIN resume_versions rv ON rv.resume_id = r.id
						WHERE r.guest_session_id = :ownerId
						ORDER BY r.created_at DESC, rv.version_no DESC
						""";
		return collectSummaries(jdbcClient.sql(sql)
				.param("ownerId", userId != null ? userId : guestSessionId)
				.query(this::mapJoinedRow)
				.list());
	}

	public ResumeSummary findById(UUID resumeId, UUID userId, UUID guestSessionId) {
		validateOwner(userId, guestSessionId);
		String sql = userId != null
				? """
						SELECT r.id AS resume_id, r.title, r.created_at AS resume_created_at,
						       rv.id AS version_id, rv.version_no, rv.mime, rv.size_bytes,
						       rv.extracted_text, rv.sections, rv.parse_warnings,
						       rv.created_at AS version_created_at
						FROM resumes r
						LEFT JOIN resume_versions rv ON rv.resume_id = r.id
						WHERE r.id = :resumeId AND r.user_id = :ownerId
						ORDER BY rv.version_no DESC
						"""
				: """
						SELECT r.id AS resume_id, r.title, r.created_at AS resume_created_at,
						       rv.id AS version_id, rv.version_no, rv.mime, rv.size_bytes,
						       rv.extracted_text, rv.sections, rv.parse_warnings,
						       rv.created_at AS version_created_at
						FROM resumes r
						LEFT JOIN resume_versions rv ON rv.resume_id = r.id
						WHERE r.id = :resumeId AND r.guest_session_id = :ownerId
						ORDER BY rv.version_no DESC
						""";
		List<ResumeSummary> results = collectSummaries(jdbcClient.sql(sql)
				.param("resumeId", resumeId)
				.param("ownerId", userId != null ? userId : guestSessionId)
				.query(this::mapJoinedRow)
				.list());
		if (results.isEmpty()) {
			throw resumeNotFound();
		}
		return results.getFirst();
	}

	@Transactional
	public void delete(UUID resumeId, UUID userId, UUID guestSessionId) {
		validateOwner(userId, guestSessionId);
		UUID ownedId = lockOwnedResume(resumeId, userId, guestSessionId)
				.orElseThrow(this::resumeNotFound);
		try {
			resumeFileDeletionService.deleteForResume(ownedId);
		} catch (RestClientException exception) {
			throw new ResumeUploadException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE",
					"Resume files could not be deleted because storage is unavailable.", exception);
		}
		jdbcClient.sql("DELETE FROM resumes WHERE id = :id")
				.param("id", ownedId)
				.update();
	}

	private Optional<UUID> lockOwnedResume(UUID resumeId, UUID userId, UUID guestSessionId) {
		String sql = userId != null
				? "SELECT id FROM resumes WHERE id = :id AND user_id = :ownerId FOR UPDATE"
				: "SELECT id FROM resumes WHERE id = :id AND guest_session_id = :ownerId FOR UPDATE";
		return jdbcClient.sql(sql)
				.param("id", resumeId)
				.param("ownerId", userId != null ? userId : guestSessionId)
				.query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
				.optional();
	}

	private Optional<Integer> findVersionByHash(UUID resumeId, String sha256) {
		return jdbcClient.sql("""
				SELECT version_no
				FROM resume_versions
				WHERE resume_id = :resumeId AND sha256 = :sha256
				""")
				.param("resumeId", resumeId)
				.param("sha256", sha256)
				.query((resultSet, rowNumber) -> resultSet.getInt("version_no"))
				.optional();
	}

	private int nextVersionNumber(UUID resumeId) {
		Integer next = jdbcClient.sql("""
				SELECT COALESCE(MAX(version_no), 0) + 1
				FROM resume_versions
				WHERE resume_id = :resumeId
				""")
				.param("resumeId", resumeId)
				.query((resultSet, rowNumber) -> resultSet.getInt(1))
				.single();
		return next == null ? 1 : next;
	}

	private void insertResume(UUID resumeId, UUID userId, UUID guestSessionId, String filename, Instant createdAt) {
		String title = titleFromFilename(filename);
		if (userId != null) {
			jdbcClient.sql("""
					INSERT INTO resumes (id, user_id, title, created_at)
					VALUES (:id, :ownerId, :title, :createdAt)
					""")
				.param("id", resumeId)
				.param("ownerId", userId)
				.param("title", title)
				.param("createdAt", Timestamp.from(createdAt))
				.update();
		} else {
			jdbcClient.sql("""
					INSERT INTO resumes (id, guest_session_id, title, created_at)
					VALUES (:id, :ownerId, :title, :createdAt)
					""")
				.param("id", resumeId)
				.param("ownerId", guestSessionId)
				.param("title", title)
				.param("createdAt", Timestamp.from(createdAt))
				.update();
		}
	}

	private void insertVersion(
			UUID versionId,
			UUID resumeId,
			int versionNo,
			String storageFileId,
			String sha256,
			String contentType,
			int sizeBytes,
			Instant createdAt) {
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, storage_file_id, sha256, mime, size_bytes, created_at)
				VALUES (:id, :resumeId, :versionNo, :storageFileId, :sha256, :mime, :sizeBytes, :createdAt)
				""")
			.param("id", versionId)
			.param("resumeId", resumeId)
			.param("versionNo", versionNo)
			.param("storageFileId", storageFileId)
			.param("sha256", sha256)
			.param("mime", contentType)
			.param("sizeBytes", sizeBytes)
			.param("createdAt", Timestamp.from(createdAt))
			.update();
	}

	private JoinedResumeRow mapJoinedRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		UUID versionId = rs.getObject("version_id", UUID.class);
		ResumeVersionSummary version = versionId == null
				? null
				: new ResumeVersionSummary(
						versionId,
						rs.getInt("version_no"),
						rs.getString("mime"),
						rs.getInt("size_bytes"),
						rs.getString("extracted_text"),
						rs.getString("sections"),
						rs.getString("parse_warnings"),
						rs.getTimestamp("version_created_at").toInstant());
		return new JoinedResumeRow(
				rs.getObject("resume_id", UUID.class),
				rs.getString("title"),
				rs.getTimestamp("resume_created_at").toInstant(),
				version);
	}

	private List<ResumeSummary> collectSummaries(List<JoinedResumeRow> rows) {
		Map<UUID, ResumeBuilder> grouped = new LinkedHashMap<>();
		for (JoinedResumeRow row : rows) {
			ResumeBuilder builder = grouped.computeIfAbsent(
					row.resumeId(),
					ignored -> new ResumeBuilder(row.resumeId(), row.title(), row.createdAt()));
			if (row.version() != null) {
				builder.versions.add(row.version());
			}
		}
		return grouped.values().stream()
				.map(b -> new ResumeSummary(b.id, b.title, b.createdAt, b.versions))
				.toList();
	}

	private void validateOwner(UUID userId, UUID guestSessionId) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new ResumeUploadException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to access resumes.");
		}
	}

	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private boolean hasPdfSignature(byte[] bytes) {
		return bytes.length >= 5
				&& bytes[0] == '%'
				&& bytes[1] == 'P'
				&& bytes[2] == 'D'
				&& bytes[3] == 'F'
				&& bytes[4] == '-';
	}

	private boolean hasZipSignature(byte[] bytes) {
		return bytes.length >= 4
				&& bytes[0] == 'P'
				&& bytes[1] == 'K'
				&& bytes[2] == 3
				&& bytes[3] == 4;
	}

	private String titleFromFilename(String filename) {
		int extensionStart = filename.lastIndexOf('.');
		String title = extensionStart > 0 ? filename.substring(0, extensionStart) : filename;
		return title.length() <= 255 ? title : title.substring(0, 255);
	}

	private ResumeUploadException tooLarge() {
		return new ResumeUploadException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
				"Resume files must be 5 MB or smaller.");
	}

	private ResumeUploadException unsupportedFile() {
		return new ResumeUploadException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
				"Only valid PDF and DOCX files are accepted.");
	}

	private ResumeUploadException resumeNotFound() {
		return new ResumeUploadException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND",
				"The resume was not found.");
	}

	private record JoinedResumeRow(
			UUID resumeId,
			String title,
			Instant createdAt,
			ResumeVersionSummary version) {
	}

	private static final class ResumeBuilder {
		private final UUID id;
		private final String title;
		private final Instant createdAt;
		private final List<ResumeVersionSummary> versions = new ArrayList<>();

		private ResumeBuilder(UUID id, String title, Instant createdAt) {
			this.id = id;
			this.title = title;
			this.createdAt = createdAt;
		}
	}
}
