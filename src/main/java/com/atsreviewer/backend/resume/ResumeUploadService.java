package com.atsreviewer.backend.resume;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.apache.poi.ooxml.POIXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.atsreviewer.backend.engine.extract.ResumeParser;

import software.amazon.awssdk.core.exception.SdkException;

@Service
public class ResumeUploadService {
	public static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;
	private static final Logger logger = LoggerFactory.getLogger(ResumeUploadService.class);

	private final JdbcClient jdbcClient;
	private final ResumeParser resumeParser;
	private final S3ResumeFileStorage fileStorage;

	public ResumeUploadService(
			JdbcClient jdbcClient,
			ResumeParser resumeParser,
			S3ResumeFileStorage fileStorage) {
		this.jdbcClient = jdbcClient;
		this.resumeParser = resumeParser;
		this.fileStorage = fileStorage;
	}

	@Transactional
	public ResumeUploadDtos.ResumeUploadResponse upload(
			UUID userId,
			UUID guestSessionId,
			MultipartFile file) {
		if ((userId == null) == (guestSessionId == null)) {
			throw new ResumeUploadException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
					"Sign in or provide a valid guest session to upload a resume.");
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

		UUID resumeId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		Instant createdAt = Instant.now();
		String extension = isPdf ? ".pdf" : ".docx";
		String storageKey = resumeId + "/" + versionId + extension;
		String fileHash = sha256(bytes);
		try {
			fileStorage.store(storageKey, bytes, contentType, fileHash);
		} catch (SdkException exception) {
			throw new ResumeUploadException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE",
					"The resume storage service is unavailable.", exception);
		}

		try {
			insertResume(resumeId, userId, guestSessionId, filename, createdAt);
			insertVersion(versionId, resumeId, storageKey, fileHash, contentType, bytes.length);
		} catch (DataAccessException exception) {
			try {
				fileStorage.delete(storageKey);
			} catch (SdkException cleanupException) {
				logger.error("Could not remove S3 object {} after its database insert failed", storageKey,
						cleanupException);
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}

		return new ResumeUploadDtos.ResumeUploadResponse(
				resumeId, versionId, filename, contentType, bytes.length, createdAt);
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
			String storageFileId,
			String sha256,
			String contentType,
			int sizeBytes) {
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, storage_file_id, sha256, mime, size_bytes)
				VALUES (:id, :resumeId, 1, :storageFileId, :sha256, :mime, :sizeBytes)
				""")
			.param("id", versionId)
			.param("resumeId", resumeId)
			.param("storageFileId", storageFileId)
			.param("sha256", sha256)
			.param("mime", contentType)
			.param("sizeBytes", sizeBytes)
			.update();
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
}
