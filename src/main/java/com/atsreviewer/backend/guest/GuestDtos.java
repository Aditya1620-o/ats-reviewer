package com.atsreviewer.backend.guest;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class GuestDtos {
	private GuestDtos() {
	}

	public static final class CreateGuestSessionRequest {
		@JsonProperty("captcha_token")
		private String captchaToken;
		@JsonProperty("age_confirmed_18")
		private Boolean ageConfirmed18;

		public String captchaToken() {
			return captchaToken;
		}

		public Boolean ageConfirmed18() {
			return ageConfirmed18;
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record GuestSessionResponse(String guestSessionId, String expiresAt) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ClaimGuestSessionResponse(String guestSessionId, boolean claimed) {
	}

	public record ErrorResponse(ErrorDetail error) {
	}

	public record ErrorDetail(String code, String message) {
	}
}
