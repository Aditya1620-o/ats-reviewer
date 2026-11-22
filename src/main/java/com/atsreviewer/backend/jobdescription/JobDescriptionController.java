package com.atsreviewer.backend.jobdescription;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.CreateJobDescriptionRequest;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.JobDescriptionResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/job-descriptions")
public class JobDescriptionController {
	private final JobDescriptionService jobDescriptionService;
	private final GuestSessionService guestSessionService;

	public JobDescriptionController(
			JobDescriptionService jobDescriptionService,
			GuestSessionService guestSessionService) {
		this.jobDescriptionService = jobDescriptionService;
		this.guestSessionService = guestSessionService;
	}

	@PostMapping
	public ResponseEntity<JobDescriptionResponse> create(
			@Valid @RequestBody CreateJobDescriptionRequest request,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(guestCookie, authentication);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(jobDescriptionService.create(owner.userId(), owner.guestSessionId(), request));
	}

	@GetMapping
	public List<JobDescriptionResponse> findAll(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(guestCookie, authentication);
		return jobDescriptionService.findAll(owner.userId(), owner.guestSessionId());
	}

	@GetMapping("/{id}")
	public JobDescriptionResponse findById(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(guestCookie, authentication);
		return jobDescriptionService.findById(id, owner.userId(), owner.guestSessionId());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(guestCookie, authentication);
		jobDescriptionService.delete(id, owner.userId(), owner.guestSessionId());
		return ResponseEntity.noContent().build();
	}

	private Owner resolveOwner(String guestCookie, Authentication authentication) {
		UUID userId = authenticatedUserId(authentication);
		if (userId != null) {
			return new Owner(userId, null);
		}
		UUID guestSessionId = guestSessionService.resolve(guestCookie)
				.orElseThrow(() -> new JobDescriptionException(
						HttpStatus.UNAUTHORIZED,
						"OWNER_REQUIRED",
						"Sign in or provide a valid guest session to manage job descriptions."));
		return new Owner(null, guestSessionId);
	}

	private UUID authenticatedUserId(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| "anonymousUser".equals(authentication.getName())) {
			return null;
		}
		if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
			return null;
		}
		try {
			return UUID.fromString(jwtAuthentication.getToken().getSubject());
		} catch (IllegalArgumentException exception) {
			throw new JobDescriptionException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
					"The access token does not contain a valid user ID.");
		}
	}

	private record Owner(UUID userId, UUID guestSessionId) {
	}
}
