package com.atsreviewer.backend.consent;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.atsreviewer.backend.AtsReviewerBackendApplication;
import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.resume.ResumeFileStorage;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AtsReviewerBackendApplication.class)
@AutoConfigureMockMvc
class ConsentControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private GuestSessionService guestSessionService;

	@MockitoBean
	private ResumeFileStorage fileStorage;

	@BeforeEach
	void configureImageKitStorage() {
		when(fileStorage.store(anyString(), any(byte[].class), anyString())).thenReturn("imagekit-file-id");
	}

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

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile("file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated());

		mockMvc.perform(delete("/v1/consent")
				.cookie(new jakarta.servlet.http.Cookie("guest_session", guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true));
		verify(fileStorage).delete("imagekit-file-id");
	}

	@Test
	void deletesImageKitFilesWhenExpiredGuestIsPurged() throws Exception {
		String guestCookie = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.createObjectNode()
						.put("captcha_token", "test")
						.toString()))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
		UUID guestSessionId = UUID.fromString(guestCookie.substring(0, guestCookie.indexOf('.')));

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile("file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated());
		jdbcClient.sql("UPDATE guest_sessions SET expires_at = :expiresAt WHERE id = :id")
				.param("expiresAt", Timestamp.from(Instant.now().minusSeconds(1)))
				.param("id", guestSessionId)
				.update();

		guestSessionService.purgeExpiredGuests();

		verify(fileStorage).delete("imagekit-file-id");
	}

	private byte[] validPdf() throws Exception {
		try (PDDocument document = new PDDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.save(output);
			return output.toByteArray();
		}
	}
}
