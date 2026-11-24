package com.atsreviewer.backend.resume;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.resume.ResumeUploadDtos.ResumeUploadResponse;
import com.atsreviewer.backend.ratelimit.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

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
			@CookieValue(name = GuestSessionService.COOKIE_NAME, required = false) String guestCookie,
			Authentication authentication,
			HttpServletRequest servletRequest) {
		UUID userId = authenticatedUserId(authentication);
		UUID guestSessionId = userId == null
				? guestSessionService.resolve(guestCookie).orElseThrow(() ->
						new ResumeUploadException(HttpStatus.UNAUTHORIZED, "OWNER_REQUIRED",
								"Sign in or provide a valid guest session to upload a resume."))
				: null;
		rateLimiter.checkUpload(userId, guestSessionId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(resumeUploadService.upload(userId, guestSessionId, file));
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
}
