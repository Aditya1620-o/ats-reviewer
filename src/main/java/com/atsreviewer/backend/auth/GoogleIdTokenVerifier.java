package com.atsreviewer.backend.auth;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdTokenVerifier {
	private final JwtDecoder jwtDecoder;
	private final String clientId;

	public GoogleIdTokenVerifier(
			@Qualifier("googleIdTokenDecoder") JwtDecoder jwtDecoder,
			@Value("${app.auth.google-client-id:}") String clientId) {
		this.jwtDecoder = jwtDecoder;
		this.clientId = clientId;
	}

	public GoogleIdentity verify(String idToken) {
		if (clientId == null || clientId.isBlank()) {
			throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_SIGN_IN_UNAVAILABLE",
					"Google sign-in is not configured.");
		}

		Jwt jwt;
		try {
			jwt = jwtDecoder.decode(idToken);
		} catch (JwtException exception) {
			throw invalidGoogleToken();
		}

		String subject = jwt.getSubject();
		String email = jwt.getClaimAsString("email");
		Boolean emailVerified = jwt.getClaim("email_verified");
		if (subject == null || subject.isBlank()
				|| email == null || email.isBlank()
				|| !Boolean.TRUE.equals(emailVerified)) {
			throw invalidGoogleToken();
		}
		return new GoogleIdentity(subject, email.trim().toLowerCase(Locale.ROOT));
	}

	private AuthException invalidGoogleToken() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
				"The Google ID token is invalid or does not contain a verified email.");
	}

	public record GoogleIdentity(String subject, String email) {
	}
}
