package com.atsreviewer.backend.guest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import com.atsreviewer.backend.AtsReviewerBackendApplication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.verify;
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

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private GuestSessionService guestSessionService;

	@MockitoBean
	private S3Client s3Client;

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

	@Test
	void removesExpiredGuestResumeFilesFromS3BeforePurgingRows() {
		UUID guestSessionId = UUID.randomUUID();
		UUID resumeId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		Instant now = Instant.now();
		String storageKey = resumeId + "/" + versionId + ".pdf";
		jdbcClient.sql("""
				INSERT INTO guest_sessions (id, ip_hash, created_at, expires_at)
				VALUES (:id, :ipHash, :createdAt, :expiresAt)
				""")
			.param("id", guestSessionId)
			.param("ipHash", "a".repeat(64))
			.param("createdAt", Timestamp.from(now.minusSeconds(7200)))
			.param("expiresAt", Timestamp.from(now.minusSeconds(3600)))
			.update();
		jdbcClient.sql("""
				INSERT INTO resumes (id, guest_session_id, title, created_at)
				VALUES (:id, :guestSessionId, :title, :createdAt)
				""")
			.param("id", resumeId)
			.param("guestSessionId", guestSessionId)
			.param("title", "resume")
			.param("createdAt", Timestamp.from(now.minusSeconds(7200)))
			.update();
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, s3_key, sha256, mime, size_bytes)
				VALUES (:id, :resumeId, 1, :storageKey, :sha256, :mime, :sizeBytes)
				""")
			.param("id", versionId)
			.param("resumeId", resumeId)
			.param("storageKey", storageKey)
			.param("sha256", "b".repeat(64))
			.param("mime", "application/pdf")
			.param("sizeBytes", 100)
			.update();

		guestSessionService.purgeExpiredGuests();

		ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client).deleteObject(requestCaptor.capture());
		assertEquals(storageKey, requestCaptor.getValue().key());
	}
}
