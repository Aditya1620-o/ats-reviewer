package com.atsreviewer.backend.resume;

import java.time.Instant;
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
			String filename,
			String mimeType,
			long sizeBytes,
			Instant createdAt) {
	}
}
