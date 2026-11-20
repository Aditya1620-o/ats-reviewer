package com.atsreviewer.backend.consent;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class ConsentDtos {
	private ConsentDtos() {
	}

	public static final class ConsentRequest {
		@JsonProperty("age_confirmed_18")
		@NotNull
		private Boolean ageConfirmed18;

		public Boolean ageConfirmed18() {
			return ageConfirmed18;
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ConsentResponse(boolean consented, String noticeVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record WithdrawalResponse(boolean deleted) {
	}

	public record ErrorResponse(ErrorDetail error) {
	}

	public record ErrorDetail(String code, String message) {
	}
}
