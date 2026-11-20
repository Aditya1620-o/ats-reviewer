package com.atsreviewer.backend.guest;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.guest.GuestDtos.ClaimGuestSessionResponse;
import com.atsreviewer.backend.guest.GuestDtos.CreateGuestSessionRequest;
import com.atsreviewer.backend.guest.GuestDtos.GuestSessionResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1")
public class GuestSessionController {
	private final GuestSessionService guestSessionService;
	private final boolean cookieSecure;

	public GuestSessionController(
			GuestSessionService guestSessionService,
			@org.springframework.beans.factory.annotation.Value("${app.guest.cookie-secure:false}") boolean cookieSecure) {
		this.guestSessionService = guestSessionService;
		this.cookieSecure = cookieSecure;
	}

	@PostMapping("/guest-sessions")
	public ResponseEntity<GuestSessionResponse> create(
			@RequestBody(required = false) CreateGuestSessionRequest request,
			HttpServletRequest httpRequest) {
		String captchaToken = request == null ? null : request.captchaToken();
		if (request != null && Boolean.FALSE.equals(request.ageConfirmed18())) {
			throw new GuestSessionException(HttpStatus.UNPROCESSABLE_ENTITY, "AGE_CONFIRMATION_REQUIRED",
					"You must be at least 18 years old to use this service.");
		}
		String clientIp = resolveClientIp(httpRequest);
		GuestSessionService.GuestRecord guest = guestSessionService.create(captchaToken, clientIp);

		ResponseCookie cookie = ResponseCookie.from(GuestSessionService.COOKIE_NAME, guest.signedCookie())
				.httpOnly(true)
				.secure(cookieSecure)
				.sameSite("Lax")
				.path("/")
				.maxAge(guestSessionService.ttl())
				.build();
		return ResponseEntity.status(HttpStatus.CREATED)
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(new GuestSessionResponse(
						guest.guestSessionId().toString(),
						guest.expiresAt().toString()));
	}

	@PostMapping("/guest-sessions/claim")
	public ClaimGuestSessionResponse claim(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String signedCookie,
			Authentication authentication) {
		java.util.Optional<UUID> guestSessionId = guestSessionService.resolve(signedCookie);
		if (guestSessionId.isEmpty()) {
			throw new GuestSessionException(HttpStatus.UNAUTHORIZED, "INVALID_GUEST_SESSION",
					"The guest session is invalid or has expired.");
		}
		UUID userId;
		try {
			userId = UUID.fromString(authentication.getName());
		} catch (RuntimeException exception) {
			throw new GuestSessionException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"A valid access token is required.");
		}
		guestSessionService.claim(guestSessionId.get(), userId);
		return new ClaimGuestSessionResponse(guestSessionId.get().toString(), true);
	}

	private String resolveClientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",", 2)[0].trim();
		}
		return request.getRemoteAddr();
	}
}
