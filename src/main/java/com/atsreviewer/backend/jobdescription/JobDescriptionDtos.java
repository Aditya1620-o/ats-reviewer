package com.atsreviewer.backend.jobdescription;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class JobDescriptionDtos {
	private JobDescriptionDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CreateRequest(String title, String rawText) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Response(UUID id, String title, String rawText, Instant createdAt) {
	}
}
