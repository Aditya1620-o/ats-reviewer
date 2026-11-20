package com.atsreviewer.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class AuthDtos {
	private AuthDtos() {
	}

	public record SignupRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(min = 12, max = 72) String password) {
	}

	public record LoginRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank String password) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record TokenResponse(
			String accessToken,
			String tokenType,
			long expiresIn,
			String refreshToken,
			long refreshExpiresIn) {
	}

	public record ErrorResponse(ErrorDetail error) {
	}

	public record ErrorDetail(String code, String message) {
	}
}