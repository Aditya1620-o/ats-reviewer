package com.atsreviewer.backend.auth;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class GoogleIdTokenConfiguration {
	private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
	private static final Set<String> GOOGLE_ISSUERS = Set.of(
			"https://accounts.google.com",
			"accounts.google.com");

	@Bean("googleIdTokenDecoder")
	JwtDecoder googleIdTokenDecoder(
			@Value("${app.auth.google-client-id:}") String clientId) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(),
				googleClaimsValidator(clientId)));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> googleClaimsValidator(String clientId) {
		return token -> {
			Object issuerClaim = token.getClaims().get("iss");
			String issuer = issuerClaim == null ? "" : issuerClaim.toString();
			if (!GOOGLE_ISSUERS.contains(issuer)) {
				return invalidToken("Google ID token issuer is invalid.");
			}
			if (clientId == null || clientId.isBlank() || !token.getAudience().contains(clientId)) {
				return invalidToken("Google ID token audience is invalid.");
			}
			String authorizedParty = token.getClaimAsString("azp");
			if ((token.getAudience().size() > 1 || authorizedParty != null)
					&& !clientId.equals(authorizedParty)) {
				return invalidToken("Google ID token authorized party is invalid.");
			}
			if (token.getExpiresAt() == null || token.getSubject() == null || token.getSubject().isBlank()) {
				return invalidToken("Google ID token is missing required claims.");
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

	private static OAuth2TokenValidatorResult invalidToken(String description) {
		return OAuth2TokenValidatorResult.failure(
				new OAuth2Error("invalid_token", description, null));
	}
}
