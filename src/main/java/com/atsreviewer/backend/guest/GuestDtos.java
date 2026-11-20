package com.atsreviewer.backend.guest;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class GuestDtos {
	private GuestDtos() {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CreateGuestSessionRequest(String captchaToken) {
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
