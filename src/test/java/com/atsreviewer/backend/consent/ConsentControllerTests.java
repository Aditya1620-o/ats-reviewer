package com.atsreviewer.backend.consent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atsreviewer.backend.AtsReviewerBackendApplication;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = AtsReviewerBackendApplication.class)
@AutoConfigureMockMvc
class ConsentControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	void under18GuestSessionIsRejectedWithoutStoringData() throws Exception {
		mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode()
						.put("captcha_token", "test")
						.put("age_confirmed_18", false)
						.toString()))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("AGE_CONFIRMATION_REQUIRED"));
	}

	@Test
	void guestConsentAndWithdrawalAreHandled() throws Exception {
		mockMvc.perform(post("/v1/consent")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode()
						.put("age_confirmed_18", true)
						.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("GUEST_SESSION_REQUIRED"));

		String guestCookie = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode()
						.put("captcha_token", "test")
						.put("age_confirmed_18", true)
						.toString()))
			.andReturn().getResponse().getCookie("guest_session").getValue();

		mockMvc.perform(post("/v1/consent")
				.cookie(new jakarta.servlet.http.Cookie("guest_session", guestCookie))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode()
						.put("age_confirmed_18", true)
						.toString()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.consented").value(true));

		mockMvc.perform(delete("/v1/consent")
				.cookie(new jakarta.servlet.http.Cookie("guest_session", guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true));
	}
}
