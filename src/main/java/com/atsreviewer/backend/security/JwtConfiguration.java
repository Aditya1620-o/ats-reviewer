package com.atsreviewer.backend.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class JwtConfiguration {
	public static final String ISSUER = "ats-reviewer";

	@Bean
	SecretKey jwtSecretKey(@Value("${app.auth.jwt-secret}") String encodedSecret) {
		if (encodedSecret == null || encodedSecret.isBlank()) {
			throw new IllegalStateException("JWT_SECRET must be configured with a base64-encoded secret.");
		}

		byte[] secret;
		try {
			secret = Base64.getDecoder().decode(encodedSecret);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("JWT_SECRET must be valid Base64.", exception);
		}
		if (secret.length < 32) {
			throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes.");
		}
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		OAuth2TokenValidator<Jwt> accessTokenValidator = token ->
				"access".equals(token.getClaimAsString("token_use"))
						? OAuth2TokenValidatorResult.success()
						: OAuth2TokenValidatorResult.failure(
								new OAuth2Error("invalid_token", "Expected an access token.", null));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(ISSUER), accessTokenValidator));
		return decoder;
	}
}