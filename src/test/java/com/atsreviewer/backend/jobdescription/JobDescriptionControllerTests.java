package com.atsreviewer.backend.jobdescription;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.jobdescription.JobDescriptionDtos.CreateRequest;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class JobDescriptionControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Test
	void createsAndListsAuthenticatedUsersJobDescriptions() throws Exception {
		String accessToken = createAccountAndGetAccessToken();
		mockMvc.perform(post("/v1/job-descriptions")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateRequest("Backend Engineer", validJobText()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.title").value("Backend Engineer"))
			.andExpect(jsonPath("$.raw_text").value(validJobText()))
			.andExpect(jsonPath("$.created_at").isNotEmpty());

		mockMvc.perform(get("/v1/job-descriptions")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void guestCanListGetAndDeleteOnlyTheirOwnJobDescriptions() throws Exception {
		String ownerCookie = createGuestCookie();
		UUID jobDescriptionId = createJobDescription(ownerCookie, "Guest JD", validJobText());
		String otherCookie = createGuestCookie();

		mockMvc.perform(get("/v1/job-descriptions")
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, otherCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/v1/job-descriptions/{id}", jobDescriptionId)
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, otherCookie)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_NOT_FOUND"));
		mockMvc.perform(delete("/v1/job-descriptions/{id}", jobDescriptionId)
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, otherCookie)))
			.andExpect(status().isNotFound());

		MockCookie owner = new MockCookie(GuestSessionService.COOKIE_NAME, ownerCookie);
		mockMvc.perform(get("/v1/job-descriptions/{id}", jobDescriptionId).cookie(owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(jobDescriptionId.toString()));
		mockMvc.perform(delete("/v1/job-descriptions/{id}", jobDescriptionId).cookie(owner))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/v1/job-descriptions/{id}", jobDescriptionId).cookie(owner))
			.andExpect(status().isNotFound());
	}

	@Test
	void rejectsJobDescriptionOutsideAllowedLength() throws Exception {
		MockCookie owner = new MockCookie(GuestSessionService.COOKIE_NAME, createGuestCookie());

		mockMvc.perform(post("/v1/job-descriptions")
				.cookie(owner)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateRequest("Short JD", "x".repeat(199)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_TOO_SHORT"));

		mockMvc.perform(post("/v1/job-descriptions")
				.cookie(owner)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateRequest("Long JD", "x".repeat(10_001)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_TOO_LONG"));
	}

	@Test
	void acceptsBothTextLengthBoundaries() throws Exception {
		String guestCookie = createGuestCookie();
		UUID minimumLengthId = createJobDescription(guestCookie, "Minimum JD", "x".repeat(200));
		UUID maximumLengthId = createJobDescription(guestCookie, "Maximum JD", "x".repeat(10_000));

		String minimumResponse = mockMvc.perform(get("/v1/job-descriptions/{id}", minimumLengthId)
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String maximumResponse = mockMvc.perform(get("/v1/job-descriptions/{id}", maximumLengthId)
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertEquals(200, objectMapper.readTree(minimumResponse).path("raw_text").stringValue().length());
		assertEquals(10_000, objectMapper.readTree(maximumResponse).path("raw_text").stringValue().length());
	}

	@Test
	void requiresAnAuthenticatedUserOrValidGuestSession() throws Exception {
		mockMvc.perform(get("/v1/job-descriptions"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("OWNER_REQUIRED"));
	}

	private UUID createJobDescription(String guestCookie, String title, String rawText) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/job-descriptions")
				.cookie(new MockCookie(GuestSessionService.COOKIE_NAME, guestCookie))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateRequest(title, rawText))))
			.andExpect(status().isCreated())
			.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.path("id")
				.stringValue());
	}

	private String createGuestCookie() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"captcha_token\":\"test\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		return result.getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
	}

	private String createAccountAndGetAccessToken() throws Exception {
		String signupResponse = mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + UUID.randomUUID() + "@example.com\","
						+ "\"password\":\"long-enough-password\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(signupResponse)
				.path("access_token")
				.stringValue();
	}

	private String validJobText() {
		return "We are looking for a backend engineer to design, build, and maintain reliable services. "
				.repeat(4).substring(0, 240);
	}
}
