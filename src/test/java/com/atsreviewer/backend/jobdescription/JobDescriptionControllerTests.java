package com.atsreviewer.backend.jobdescription;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.atsreviewer.backend.guest.GuestSessionService;

import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobDescriptionControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	void guestCanCreateListGetAndDeleteJobDescription() throws Exception {
		String guestCookie = createGuestCookie();
		String rawText = "Java is required. Python is preferred. " + "x".repeat(165);
		String request = request("Java Developer", rawText);
		String response = mockMvc.perform(post("/v1/job-descriptions")
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie))
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.job_description_id").isNotEmpty())
			.andExpect(jsonPath("$.title").value("Java Developer"))
			.andExpect(jsonPath("$.raw_text").value(rawText))
			.andExpect(jsonPath("$.required_skill_ids[0]").value(1))
			.andExpect(jsonPath("$.preferred_skill_ids[0]").value(2))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = objectMapper.readTree(response).path("job_description_id").stringValue();

		mockMvc.perform(get("/v1/job-descriptions")
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].job_description_id").value(id))
			.andExpect(jsonPath("$[0].required_skill_ids[0]").value(1))
			.andExpect(jsonPath("$[0].preferred_skill_ids[0]").value(2));

		mockMvc.perform(get("/v1/job-descriptions/{id}", id)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.job_description_id").value(id));

		mockMvc.perform(delete("/v1/job-descriptions/{id}", id)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/job-descriptions")
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void authenticatedUsersCannotReadOrDeleteAnotherUsersDescription() throws Exception {
		String ownerToken = createAccountToken();
		String otherToken = createAccountToken();
		String response = mockMvc.perform(post("/v1/job-descriptions")
				.header("Authorization", "Bearer " + ownerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Java Developer", "x".repeat(200))))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = objectMapper.readTree(response).path("job_description_id").stringValue();

		mockMvc.perform(get("/v1/job-descriptions/{id}", id)
				.header("Authorization", "Bearer " + otherToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_NOT_FOUND"));

		mockMvc.perform(delete("/v1/job-descriptions/{id}", id)
				.header("Authorization", "Bearer " + otherToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_NOT_FOUND"));
	}

	@Test
	void rejectsMissingOwnerAndInvalidDescriptionText() throws Exception {
		mockMvc.perform(get("/v1/job-descriptions"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("OWNER_REQUIRED"));

		mockMvc.perform(post("/v1/job-descriptions")
				.cookie(new jakarta.servlet.http.Cookie(
						GuestSessionService.COOKIE_NAME, createGuestCookie()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Java Developer", "too short")))
			.andExpect(status().isBadRequest());
	}

	private String createGuestCookie() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"captcha_token\":\"test\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		return result.getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
	}

	private String createAccountToken() throws Exception {
		String response = mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + UUID.randomUUID() + "@example.com\","
						+ "\"password\":\"long-enough-password\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(response).path("access_token").stringValue();
	}

	private String request(String title, String rawText) throws Exception {
		return objectMapper.writeValueAsString(new JobDescriptionDtos.CreateJobDescriptionRequest(title, rawText));
	}
}
