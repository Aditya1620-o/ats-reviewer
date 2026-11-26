package com.atsreviewer.backend.resume;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class ResumeUploadDtos {
	private ResumeUploadDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ResumeUploadResponse(
			UUID resumeId,
			UUID versionId,
			int versionNo,
			String filename,
			String mimeType,
			long sizeBytes,
			Instant createdAt) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ResumeVersionSummary(
			UUID versionId,
			int versionNo,
			String mimeType,
			int sizeBytes,
			String extractedText,
			String sections,
			String parseWarnings,
			Instant createdAt) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ResumeSummary(
			UUID id,
			String title,
			Instant createdAt,
			List<ResumeVersionSummary> versions) {
		public ResumeSummary {
			versions = List.copyOf(versions);
		}
	}
}
