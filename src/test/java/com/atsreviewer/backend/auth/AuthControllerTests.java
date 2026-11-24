package com.atsreviewer.backend.auth;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atsreviewer.backend.AtsReviewerBackendApplication;
import com.atsreviewer.backend.ratelimit.RateLimitTestConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.when;

@SpringBootTest(classes = AtsReviewerBackendApplication.class)
@AutoConfigureMockMvc
@Import(RateLimitTestConfiguration.class)
class AuthControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private JdbcClient jdbcClient;

	@MockitoBean
	private GoogleIdTokenVerifier googleIdTokenVerifier;

	@Test
	void signupLoginAndRefreshRotationWork() throws Exception {
		String email = uniqueEmail();
		String password = "correct-horse-battery";

		JsonNode signup = json(mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(email, password)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andReturn());

		String firstAccessToken = signup.path("access_token").stringValue();
		String firstRefreshToken = signup.path("refresh_token").stringValue();
		assertNotNull(jwtDecoder.decode(firstAccessToken).getSubject());
		assertEquals("user", jwtDecoder.decode(firstAccessToken).getClaimAsString("role"));

		mockMvc.perform(get("/v1/private-probe"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v1/private-probe")
				.header("Authorization", "Bearer " + firstAccessToken))
				.andExpect(status().isNotFound());

		JsonNode login = json(mockMvc.perform(post("/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(email.toUpperCase(), password)))
			.andExpect(status().isOk())
			.andReturn());
		assertNotNull(jwtDecoder.decode(login.path("access_token").stringValue()).getSubject());

		JsonNode refreshed = json(mockMvc.perform(post("/v1/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(refreshRequest(firstRefreshToken)))
			.andExpect(status().isOk())
			.andReturn());
		assertNotEquals(firstRefreshToken, refreshed.path("refresh_token").stringValue());

		mockMvc.perform(post("/v1/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(refreshRequest(firstRefreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void duplicateSignupAndLogoutAreHandled() throws Exception {
		String email = uniqueEmail();
		String password = "long-enough-password";
		String signupBody = request(email, password);

		JsonNode tokens = json(mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupBody))
			.andExpect(status().isCreated())
			.andReturn());

		mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupBody))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EMAIL_TAKEN"));

		String refreshToken = tokens.path("refresh_token").stringValue();
		mockMvc.perform(post("/v1/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content(refreshRequest(refreshToken)))
			.andExpect(status().isNoContent());

		mockMvc.perform(post("/v1/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(refreshRequest(refreshToken)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void googleSignInCreatesAnAccountAndReturnsApplicationTokens() throws Exception {
		String email = uniqueEmail();
		String googleSubject = "google-sub-" + UUID.randomUUID();
		when(googleIdTokenVerifier.verify("google-id-token"))
				.thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(googleSubject, email));

		JsonNode tokens = json(mockMvc.perform(post("/v1/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content(googleRequest("google-id-token")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token_type").value("Bearer"))
			.andReturn());

		UUID userId = UUID.fromString(jwtDecoder.decode(tokens.path("access_token").stringValue()).getSubject());
		AccountIdentity account = jdbcClient.sql("""
				SELECT email, password_hash, google_sub
				FROM users
				WHERE id = :id
				""")
			.param("id", userId)
			.query((resultSet, rowNumber) -> new AccountIdentity(
					resultSet.getString("email"),
					resultSet.getString("password_hash"),
					resultSet.getString("google_sub")))
			.single();
		assertEquals(email, account.email());
		assertNull(account.passwordHash());
		assertEquals(googleSubject, account.googleSubject());
	}

	@Test
	void googleSignInLinksAVerifiedIdentityToAnExistingEmailAccount() throws Exception {
		String email = uniqueEmail();
		JsonNode existingTokens = json(mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(email, "long-enough-password")))
			.andExpect(status().isCreated())
			.andReturn());
		UUID existingUserId = UUID.fromString(
				jwtDecoder.decode(existingTokens.path("access_token").stringValue()).getSubject());
		String googleSubject = "google-sub-" + UUID.randomUUID();
		when(googleIdTokenVerifier.verify("google-id-token"))
				.thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(googleSubject, email.toUpperCase()));

		JsonNode googleTokens = json(mockMvc.perform(post("/v1/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content(googleRequest("google-id-token")))
			.andExpect(status().isOk())
			.andReturn());

		assertEquals(existingUserId,
				UUID.fromString(jwtDecoder.decode(googleTokens.path("access_token").stringValue()).getSubject()));
		assertEquals(googleSubject, jdbcClient.sql("SELECT google_sub FROM users WHERE id = :id")
				.param("id", existingUserId)
				.query((resultSet, rowNumber) -> resultSet.getString("google_sub"))
				.single());
	}

	@Test
	void googleIdentityCannotBeLinkedToAnEmailAlreadyLinkedToAnotherIdentity() throws Exception {
		String email = uniqueEmail();
		when(googleIdTokenVerifier.verify("first-id-token"))
				.thenReturn(new GoogleIdTokenVerifier.GoogleIdentity("google-sub-1", email));
		mockMvc.perform(post("/v1/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content(googleRequest("first-id-token")))
			.andExpect(status().isOk());

		when(googleIdTokenVerifier.verify("second-id-token"))
				.thenReturn(new GoogleIdTokenVerifier.GoogleIdentity("google-sub-2", email));
		mockMvc.perform(post("/v1/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content(googleRequest("second-id-token")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_LINK_CONFLICT"));
	}

	private String request(String email, String password) throws Exception {
		return objectMapper.writeValueAsString(new Credentials(email, password));
	}

	private String googleRequest(String idToken) throws Exception {
		return objectMapper.writeValueAsString(new AuthDtos.GoogleLoginRequest(idToken));
	}

	private String refreshRequest(String refreshToken) throws Exception {
		return objectMapper.createObjectNode()
				.put("refresh_token", refreshToken)
				.toString();
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String uniqueEmail() {
		return UUID.randomUUID() + "@example.com";
	}

	private record Credentials(String email, String password) {
	}

	private record AccountIdentity(String email, String passwordHash, String googleSubject) {
	}
}