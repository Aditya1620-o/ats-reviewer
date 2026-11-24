package com.atsreviewer.backend.auth;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleIdTokenVerifierTests {
	private static final String CLIENT_ID = "frontend-client-id";

	@Test
	void returnsNormalizedEmailForVerifiedGoogleIdentity() {
		JwtDecoder decoder = mock(JwtDecoder.class);
		when(decoder.decode("id-token")).thenReturn(token(true));
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(decoder, CLIENT_ID);

		GoogleIdTokenVerifier.GoogleIdentity identity = verifier.verify("id-token");

		assertEquals("google-subject", identity.subject());
		assertEquals("person@example.com", identity.email());
	}

	@Test
	void rejectsUnverifiedGoogleEmail() {
		JwtDecoder decoder = mock(JwtDecoder.class);
		when(decoder.decode("id-token")).thenReturn(token(false));
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(decoder, CLIENT_ID);

		AuthException exception = assertThrows(AuthException.class, () -> verifier.verify("id-token"));

		assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
		assertEquals("INVALID_GOOGLE_TOKEN", exception.code());
	}

	@Test
	void rejectsAProviderTokenThatFailsSignatureOrClaimValidation() {
		JwtDecoder decoder = mock(JwtDecoder.class);
		when(decoder.decode("invalid-token")).thenThrow(new JwtException("invalid token"));
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(decoder, CLIENT_ID);

		AuthException exception = assertThrows(AuthException.class, () -> verifier.verify("invalid-token"));

		assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
		assertEquals("INVALID_GOOGLE_TOKEN", exception.code());
	}

	@Test
	void reportsUnavailableWhenGoogleClientIsNotConfigured() {
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(mock(JwtDecoder.class), "");

		AuthException exception = assertThrows(AuthException.class, () -> verifier.verify("id-token"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
		assertEquals("GOOGLE_SIGN_IN_UNAVAILABLE", exception.code());
	}

	private Jwt token(Object verifiedClaim) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("id-token")
				.header("alg", "RS256")
				.subject("google-subject")
				.claim("email", " Person@Example.com ")
				.claim("email_verified", verifiedClaim)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.build();
	}
}
