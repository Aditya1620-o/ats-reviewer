package com.atsreviewer.backend.analysis;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.analysis.SharedReportDtos.PublicReport;
import com.atsreviewer.backend.analysis.SharedReportDtos.ShareResponse;
import com.atsreviewer.backend.guest.GuestSessionService;

@RestController
public class SharedReportController {
	private final SharedReportService sharedReportService;
	private final GuestSessionService guestSessionService;

	public SharedReportController(
			SharedReportService sharedReportService,
			GuestSessionService guestSessionService) {
		this.sharedReportService = sharedReportService;
		this.guestSessionService = guestSessionService;
	}

	@PostMapping("/v1/analyses/{analysisId}/share")
	public ResponseEntity<ShareResponse> create(
			@PathVariable UUID analysisId,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(sharedReportService.create(owner.userId(), owner.guestSessionId(), analysisId));
	}

	@GetMapping("/v1/shared-reports/{token}")
	public PublicReport get(@PathVariable String token) {
		return sharedReportService.get(token);
	}

	@DeleteMapping("/v1/shared-reports/{token}")
	public ResponseEntity<Void> revoke(
			@PathVariable String token,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		sharedReportService.revoke(owner.userId(), owner.guestSessionId(), token);
		return ResponseEntity.noContent().build();
	}

	private Owner resolveOwner(Authentication authentication, String guestCookie) {
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			try {
				return new Owner(UUID.fromString(jwtAuthentication.getToken().getSubject()), null);
			} catch (IllegalArgumentException exception) {
				throw new AnalysisException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
						"The access token does not contain a valid user ID.");
			}
		}
		UUID guestSessionId = guestSessionService.resolve(guestCookie).orElseThrow(() ->
				new AnalysisException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
						"Sign in or provide a valid guest session to manage shared reports."));
		return new Owner(null, guestSessionId);
	}

	private record Owner(UUID userId, UUID guestSessionId) {
	}
}
