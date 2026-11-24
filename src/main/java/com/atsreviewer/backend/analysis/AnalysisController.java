package com.atsreviewer.backend.analysis;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.analysis.AnalysisDtos.CreateRequest;
import com.atsreviewer.backend.analysis.AnalysisDtos.Response;
import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.ratelimit.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/analyses")
public class AnalysisController {
	private final AnalysisService analysisService;
	private final GuestSessionService guestSessionService;
	private final RateLimiter rateLimiter;

	public AnalysisController(
			AnalysisService analysisService,
			GuestSessionService guestSessionService,
			RateLimiter rateLimiter) {
		this.analysisService = analysisService;
		this.guestSessionService = guestSessionService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping
	public ResponseEntity<Response> create(
			@RequestBody(required = false) CreateRequest request,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication,
			HttpServletRequest servletRequest) {
		Owner owner = resolveOwner(authentication, guestCookie);
		rateLimiter.checkAnalysis(owner.userId(), owner.guestSessionId(), servletRequest.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(analysisService.create(owner.userId(), owner.guestSessionId(), request));
	}

	@GetMapping("/{id}")
	public Response get(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return analysisService.get(owner.userId(), owner.guestSessionId(), id);
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
						"Sign in or provide a valid guest session to access analyses."));
		return new Owner(null, guestSessionId);
	}

	private record Owner(UUID userId, UUID guestSessionId) {
	}
}
