package com.atsreviewer.backend.resume;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.ratelimit.RateLimiter;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeSummary;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeUploadResponse;

@RestController
@RequestMapping("/v1/resumes")
public class ResumeUploadController {
	private final ResumeUploadService resumeUploadService;
	private final GuestSessionService guestSessionService;
	private final RateLimiter rateLimiter;

	public ResumeUploadController(
			ResumeUploadService resumeUploadService,
			GuestSessionService guestSessionService,
			RateLimiter rateLimiter) {
		this.resumeUploadService = resumeUploadService;
		this.guestSessionService = guestSessionService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ResumeUploadResponse> upload(
			@RequestPart("file") MultipartFile file,
			@RequestParam(name = "resume_id", required = false) UUID resumeId,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		rateLimiter.checkUpload(owner.userId(), owner.guestSessionId());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(resumeUploadService.upload(owner.userId(), owner.guestSessionId(), resumeId, file));
	}

	@PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ResumeUploadResponse> uploadVersion(
			@PathVariable UUID id,
			@RequestPart("file") MultipartFile file,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		rateLimiter.checkUpload(owner.userId(), owner.guestSessionId());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(resumeUploadService.upload(owner.userId(), owner.guestSessionId(), id, file));
	}

	@GetMapping
	public List<ResumeSummary> findAll(
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return resumeUploadService.findAll(owner.userId(), owner.guestSessionId());
	}

	@GetMapping("/{id}")
	public ResumeSummary findById(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		return resumeUploadService.findById(id, owner.userId(), owner.guestSessionId());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id,
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication) {
		Owner owner = resolveOwner(authentication, guestCookie);
		resumeUploadService.delete(id, owner.userId(), owner.guestSessionId());
		return ResponseEntity.noContent().build();
	}

	private Owner resolveOwner(Authentication authentication, String guestCookie) {
		UUID userId = authenticatedUserId(authentication);
		if (userId != null) {
			return new Owner(userId, null);
		}
		UUID guestSessionId = guestSessionService.resolve(guestCookie).orElseThrow(() ->
				new ResumeUploadException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
						"Sign in or provide a valid guest session to upload a resume."));
		return new Owner(null, guestSessionId);
	}

	private UUID authenticatedUserId(Authentication authentication) {
		if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
			return null;
		}
		try {
			return UUID.fromString(jwtAuthentication.getToken().getSubject());
		} catch (IllegalArgumentException exception) {
			throw new ResumeUploadException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
					"The access token does not contain a valid user ID.");
		}
	}

	private record Owner(UUID userId, UUID guestSessionId) {
	}
}
