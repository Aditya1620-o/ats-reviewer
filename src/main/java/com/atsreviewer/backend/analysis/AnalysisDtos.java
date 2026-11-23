package com.atsreviewer.backend.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class AnalysisDtos {
	private AnalysisDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CreateRequest(UUID resumeVersionId, UUID jobDescriptionId) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Response(
			UUID id,
			String status,
			String engineVersion,
			BigDecimal score,
			String errorCode,
			Instant createdAt,
			Instant startedAt,
			Instant finishedAt,
			List<Item> items) {
		public Response {
			items = List.copyOf(items);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Item(
			int skillId,
			String skillName,
			BigDecimal weight,
			String status,
			String foundIn,
			BigDecimal points) {
	}
}
