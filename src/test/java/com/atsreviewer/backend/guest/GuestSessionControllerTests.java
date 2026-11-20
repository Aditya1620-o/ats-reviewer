package com.atsreviewer.backend.guest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.atsreviewer.backend.AtsReviewerBackendApplication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AtsReviewerBackendApplication.class)
@AutoConfigureMockMvc
class GuestSessionControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	void createsSignedHttpOnlyGuestCookie() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode().put("captcha_token", "test").toString()))
			.andExpect(status().isCreated())
			.andExpect(cookie().exists("guest_session"))
			.andExpect(cookie().httpOnly("guest_session", true))
			.andExpect(jsonPath("$.guest_session_id").isNotEmpty())
			.andExpect(jsonPath("$.expires_at").isNotEmpty())
			.andReturn();

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertNotNull(UUID.fromString(body.path("guest_session_id").stringValue()));
		assertEquals(1, result.getResponse().getCookies().length);
	}

	@Test
	void rejectsMissingCaptchaTokenWhenCaptchaIsEnabled() throws Exception {
		mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("CAPTCHA_REQUIRED"));
	}
}
