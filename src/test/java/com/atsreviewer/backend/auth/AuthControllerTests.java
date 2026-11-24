package com.atsreviewer.backend.auth;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

	private String request(String email, String password) throws Exception {
		return objectMapper.writeValueAsString(new Credentials(email, password));
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

}