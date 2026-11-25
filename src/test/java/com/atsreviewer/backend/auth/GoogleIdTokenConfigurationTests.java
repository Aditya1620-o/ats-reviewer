package com.atsreviewer.backend.auth;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleIdTokenConfigurationTests {
	private static final String CLIENT_ID = "frontend-client-id";

	@Test
	void acceptsGoogleIssuerAndConfiguredAudience() {
		assertFalse(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(token("https://accounts.google.com", CLIENT_ID))
				.hasErrors());
		assertFalse(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(token("accounts.google.com", CLIENT_ID))
				.hasErrors());
	}

	@Test
	void rejectsOtherIssuersAndAudiences() {
		assertTrue(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(token("https://attacker.example", CLIENT_ID))
				.hasErrors());
		assertTrue(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(token("https://accounts.google.com", "different-client"))
				.hasErrors());
		assertTrue(GoogleIdTokenConfiguration.googleClaimsValidator("")
				.validate(token("https://accounts.google.com", CLIENT_ID))
				.hasErrors());
	}

	@Test
	void requiresAuthorizedPartyWhenTokenHasMultipleAudiences() {
		Jwt validMultiAudienceToken = multiAudienceToken(CLIENT_ID);
		Jwt invalidMultiAudienceToken = token("https://accounts.google.com", CLIENT_ID, "another-client");

		assertFalse(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(validMultiAudienceToken)
				.hasErrors());
		assertTrue(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(invalidMultiAudienceToken)
				.hasErrors());
		assertTrue(GoogleIdTokenConfiguration.googleClaimsValidator(CLIENT_ID)
				.validate(multiAudienceToken("different-client"))
				.hasErrors());
	}

	private Jwt token(String issuer, String audience) {
		return token(issuer, new String[] { audience });
	}

	private Jwt token(String issuer, String... audiences) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("test-token")
				.header("alg", "RS256")
				.issuer(issuer)
				.audience(List.of(audiences))
				.subject("google-subject")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.build();
	}

	private Jwt multiAudienceToken(String authorizedParty) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("test-token")
				.header("alg", "RS256")
				.issuer("https://accounts.google.com")
				.audience(List.of(CLIENT_ID, "another-client"))
				.subject("google-subject")
				.claim("azp", authorizedParty)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.build();
	}
}
