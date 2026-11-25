package com.atsreviewer.backend.consent;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

import com.atsreviewer.backend.AtsReviewerBackendApplication;

import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentCaptor;

@SpringBootTest(classes = AtsReviewerBackendApplication.class)
@AutoConfigureMockMvc
class ConsentControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Autowired
	private JdbcClient jdbcClient;

	@MockitoBean
	private S3Client s3Client;

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

		UUID guestSessionId = UUID.fromString(guestCookie.substring(0, guestCookie.lastIndexOf('.')));
		UUID resumeId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		String storageKey = resumeId + "/" + versionId + ".pdf";
		jdbcClient.sql("""
				INSERT INTO resumes (id, guest_session_id, title, created_at)
				VALUES (:id, :guestSessionId, :title, :createdAt)
				""")
			.param("id", resumeId)
			.param("guestSessionId", guestSessionId)
			.param("title", "resume")
			.param("createdAt", Instant.now())
			.update();
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, s3_key, sha256, mime, size_bytes)
				VALUES (:id, :resumeId, 1, :storageKey, :sha256, :mime, :sizeBytes)
				""")
			.param("id", versionId)
			.param("resumeId", resumeId)
			.param("storageKey", storageKey)
			.param("sha256", "a".repeat(64))
			.param("mime", "application/pdf")
			.param("sizeBytes", 100)
			.update();

		mockMvc.perform(delete("/v1/consent")
				.cookie(new jakarta.servlet.http.Cookie("guest_session", guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true));

		ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client).deleteObject(requestCaptor.capture());
		assertEquals(storageKey, requestCaptor.getValue().key());
	}
}
