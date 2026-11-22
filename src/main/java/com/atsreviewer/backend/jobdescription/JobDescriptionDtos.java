package com.atsreviewer.backend.jobdescription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class JobDescriptionDtos {
	private JobDescriptionDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CreateJobDescriptionRequest(
			@NotBlank @Size(max = 255) String title,
			@NotBlank @Size(min = 200, max = 10_000) String rawText) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record JobDescriptionResponse(
			UUID jobDescriptionId,
			String title,
			String rawText,
			Instant createdAt,
			List<Integer> requiredSkillIds,
			List<Integer> preferredSkillIds) {
		public JobDescriptionResponse {
			requiredSkillIds = List.copyOf(requiredSkillIds);
			preferredSkillIds = List.copyOf(preferredSkillIds);
		}
	}
}
