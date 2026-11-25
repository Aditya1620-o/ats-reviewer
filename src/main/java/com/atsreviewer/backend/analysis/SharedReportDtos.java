package com.atsreviewer.backend.analysis;

import java.time.Instant;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class SharedReportDtos {
	private SharedReportDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ShareResponse(String token, Instant expiresAt) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record PublicReport(String token, Instant expiresAt, AnalysisDtos.Response analysis) {
	}
}
