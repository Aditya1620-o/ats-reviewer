package com.atsreviewer.backend.resume;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.apache.poi.ooxml.POIXMLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.atsreviewer.backend.engine.extract.ResumeParser;

@Service
public class ResumeUploadService {
	public static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;

	private final JdbcClient jdbcClient;
	private final ResumeParser resumeParser;
	private final Path uploadDirectory;

	public ResumeUploadService(
			JdbcClient jdbcClient,
			ResumeParser resumeParser,
			@Value("${app.upload.directory}") String uploadDirectory) {
		this.jdbcClient = jdbcClient;
		this.resumeParser = resumeParser;
		this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
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
		Path storedFile = uploadDirectory.resolve(resumeId.toString())
				.resolve(versionId + extension).normalize();
		if (!storedFile.startsWith(uploadDirectory)) {
			throw new IllegalStateException("Generated resume storage path escaped the upload directory.");
		}

		try {
			Files.createDirectories(storedFile.getParent());
			Files.write(storedFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (IOException exception) {
			throw new ResumeUploadException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED",
					"The resume could not be stored.");
		}

		try {
			insertResume(resumeId, userId, guestSessionId, filename, createdAt);
			insertVersion(versionId, resumeId, storageKey, sha256(bytes), contentType, bytes.length);
		} catch (DataAccessException exception) {
			try {
				Files.deleteIfExists(storedFile);
				Files.deleteIfExists(storedFile.getParent());
			} catch (IOException cleanupException) {
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
			String storageKey,
			String sha256,
			String contentType,
			int sizeBytes) {
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, s3_key, sha256, mime, size_bytes)
				VALUES (:id, :resumeId, 1, :storageKey, :sha256, :mime, :sizeBytes)
				""")
			.param("id", versionId)
			.param("resumeId", resumeId)
			.param("storageKey", storageKey)
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
