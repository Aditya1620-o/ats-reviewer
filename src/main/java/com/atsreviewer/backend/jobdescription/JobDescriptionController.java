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
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.CreateRequest;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.Response;

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
	public ResponseEntity<Response> create(
			@RequestBody(required = false) CreateRequest request,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(jobDescriptionService.create(owner.userId(), owner.guestSessionId(), request));
	}

	@GetMapping
	public List<Response> list(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return jobDescriptionService.list(owner.userId(), owner.guestSessionId());
	}

	@GetMapping("/{id}")
	public Response get(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return jobDescriptionService.get(owner.userId(), owner.guestSessionId(), id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		jobDescriptionService.delete(owner.userId(), owner.guestSessionId(), id);
		return ResponseEntity.noContent().build();
	}

	private Owner resolveOwner(Authentication authentication, String guestCookie) {
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			try {
				return new Owner(UUID.fromString(jwtAuthentication.getToken().getSubject()), null);
			} catch (IllegalArgumentException exception) {
				throw new JobDescriptionException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
						"The access token does not contain a valid user ID.");
			}
		}
		UUID guestSessionId = guestSessionService.resolve(guestCookie).orElseThrow(() ->
				new JobDescriptionException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
						"Sign in or provide a valid guest session to access job descriptions."));
		return new Owner(null, guestSessionId);
	}

	private record Owner(UUID userId, UUID guestSessionId) {
	}
}
