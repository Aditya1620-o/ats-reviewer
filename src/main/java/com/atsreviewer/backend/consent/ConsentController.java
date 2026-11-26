package com.atsreviewer.backend.consent;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.consent.ConsentDtos.ConsentRequest;
import com.atsreviewer.backend.consent.ConsentDtos.ConsentResponse;
import com.atsreviewer.backend.consent.ConsentDtos.WithdrawalResponse;
import com.atsreviewer.backend.guest.GuestSessionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/consent")
public class ConsentController {
	private final ConsentService consentService;
	private final GuestSessionService guestSessionService;

	public ConsentController(ConsentService consentService, GuestSessionService guestSessionService) {
		this.consentService = consentService;
		this.guestSessionService = guestSessionService;
	}

	@GetMapping
	public ConsentResponse status(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String signedCookie,
			Authentication authentication) {
		UUID userId = authenticatedUserId(authentication);
		UUID guestSessionId = resolveGuestSession(signedCookie);
		boolean consented = consentService.hasCurrentConsent(userId, guestSessionId);
		return new ConsentResponse(consented, consentService.noticeVersion());
	}

	@PostMapping
	public ResponseEntity<ConsentResponse> giveConsent(
			@Valid @RequestBody ConsentRequest request,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String signedCookie,
			Authentication authentication) {
		UUID userId = authenticatedUserId(authentication);
		UUID guestSessionId = resolveGuestSession(signedCookie);
		consentService.giveConsent(userId, guestSessionId, Boolean.TRUE.equals(request.ageConfirmed18()));
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new ConsentResponse(true, consentService.noticeVersion()));
	}

	@DeleteMapping
	public WithdrawalResponse withdraw(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String signedCookie,
			Authentication authentication) {
		UUID userId = authenticatedUserId(authentication);
		UUID guestSessionId = resolveGuestSession(signedCookie);
		consentService.withdraw(userId, guestSessionId);
		return new WithdrawalResponse(true);
	}

	private UUID authenticatedUserId(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| "anonymousUser".equals(authentication.getName())) {
			return null;
		}
		try {
			return UUID.fromString(authentication.getName());
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private UUID resolveGuestSession(String signedCookie) {
		return guestSessionService.resolve(signedCookie).orElse(null);
	}
}
