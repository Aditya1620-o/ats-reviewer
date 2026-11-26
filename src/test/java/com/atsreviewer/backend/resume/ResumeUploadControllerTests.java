package com.atsreviewer.backend.resume;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClientException;

import com.atsreviewer.backend.guest.GuestSessionService;
import com.atsreviewer.backend.ratelimit.RateLimitTestConfiguration;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(RateLimitTestConfiguration.class)
class ResumeUploadControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@MockitoBean
	private ResumeFileStorage fileStorage;

	@BeforeEach
	void setUpStorage() {
		when(fileStorage.store(anyString(), any(byte[].class), anyString()))
				.thenReturn("imagekit-file-id");
	}

	@Test
	void requiresConsentBeforeUploading() throws Exception {
		String guestCookie = createGuestCookieWithoutConsent();
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));

		String accessToken = signUpUserWithoutConsent();
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
	}

	@Test
	void uploadsValidPdfForGuestSession() throws Exception {
		String guestCookie = createGuestCookie();
		MockMultipartFile file = new MockMultipartFile(
				"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf());

		MvcResult result = mockMvc.perform(multipart("/v1/resumes")
				.file(file)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.resume_id").isNotEmpty())
			.andExpect(jsonPath("$.version_id").isNotEmpty())
			.andExpect(jsonPath("$.version_no").value(1))
			.andExpect(jsonPath("$.filename").value("resume.pdf"))
			.andExpect(jsonPath("$.mime_type").value(MediaType.APPLICATION_PDF_VALUE))
			.andExpect(jsonPath("$.size_bytes").isNumber())
			.andReturn();
		var response = objectMapper.readTree(result.getResponse().getContentAsString());
		verify(fileStorage).store(
				eq(response.path("resume_id").stringValue() + "/"
						+ response.path("version_id").stringValue() + ".pdf"),
				any(byte[].class),
				eq(MediaType.APPLICATION_PDF_VALUE));
	}

	@Test
	void uploadsValidDocxForGuestSession() throws Exception {
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file",
						"resume.docx",
						"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
						validDocx()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.filename").value("resume.docx"));
	}

	@Test
	void acceptsAFileExactlyFiveMegabytes() throws Exception {
		byte[] pdf = validPdf();
		byte[] exactlyFiveMegabytes = Arrays.copyOf(pdf, (int) ResumeUploadService.MAX_FILE_SIZE_BYTES);
		Arrays.fill(exactlyFiveMegabytes, pdf.length, exactlyFiveMegabytes.length, (byte) ' ');

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, exactlyFiveMegabytes))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.size_bytes").value(ResumeUploadService.MAX_FILE_SIZE_BYTES));
	}

	@Test
	void rejectsUnsupportedType() throws Exception {
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile("file", "resume.txt", MediaType.TEXT_PLAIN_VALUE, "text".getBytes()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
	}

	@Test
	void rejectsAFileWithPdfNameAndMimeButWrongContent() throws Exception {
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, "not a PDF".getBytes()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
	}

	@Test
	void rejectsFilesLargerThanFiveMegabytes() throws Exception {
		byte[] oversized = new byte[(int) ResumeUploadService.MAX_FILE_SIZE_BYTES + 1];
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, oversized))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
	}

	@Test
	void requiresAuthenticatedUserOrValidGuestSession() throws Exception {
		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("OWNER_REQUIRED"));
	}

	@Test
	void reportsStorageUnavailableWhenStorageWriteFails() throws Exception {
		doThrow(new RestClientException("storage unavailable"))
				.when(fileStorage)
				.store(anyString(), any(byte[].class), anyString());

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, createGuestCookie())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("FILE_STORAGE_UNAVAILABLE"));
	}

	@Test
	void uploadsResumeForAuthenticatedUser() throws Exception {
		String accessToken = signUpUserWithConsent();

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.resume_id").isNotEmpty())
			.andExpect(jsonPath("$.version_no").value(1));
	}

	@Test
	void supportsVersionsDuplicateRejectionListingAndDeletionWithOwnership() throws Exception {
		String guestCookie = createGuestCookie();
		String otherGuestCookie = createGuestCookie();
		byte[] v1Bytes = validPdf();
		byte[] v2Bytes = validDocx();

		when(fileStorage.store(anyString(), any(byte[].class), anyString()))
				.thenReturn("file-v1")
				.thenReturn("file-v2");

		MvcResult firstUpload = mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile("file", "my-cv.pdf", MediaType.APPLICATION_PDF_VALUE, v1Bytes))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version_no").value(1))
			.andReturn();
		String resumeId = objectMapper.readTree(firstUpload.getResponse().getContentAsString())
				.path("resume_id")
				.stringValue();

		mockMvc.perform(multipart("/v1/resumes/{id}/versions", resumeId)
				.file(new MockMultipartFile("file", "my-cv.pdf", MediaType.APPLICATION_PDF_VALUE, v1Bytes))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_VERSION"));

		mockMvc.perform(multipart("/v1/resumes/{id}/versions", resumeId)
				.file(new MockMultipartFile(
						"file",
						"my-cv-v2.docx",
						"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
						v2Bytes))
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.resume_id").value(resumeId))
			.andExpect(jsonPath("$.version_no").value(2));

		mockMvc.perform(get("/v1/resumes")
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(resumeId))
			.andExpect(jsonPath("$[0].versions.length()").value(2))
			.andExpect(jsonPath("$[0].versions[0].version_no").value(2))
			.andExpect(jsonPath("$[0].versions[1].version_no").value(1));

		mockMvc.perform(get("/v1/resumes/{id}", resumeId)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, otherGuestCookie)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESUME_NOT_FOUND"));

		mockMvc.perform(delete("/v1/resumes/{id}", resumeId)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, otherGuestCookie)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESUME_NOT_FOUND"));

		mockMvc.perform(delete("/v1/resumes/{id}", resumeId)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isNoContent());

		verify(fileStorage).delete("file-v1");
		verify(fileStorage).delete("file-v2");

		mockMvc.perform(get("/v1/resumes/{id}", resumeId)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isNotFound());
	}

	private String createGuestCookieWithoutConsent() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"captcha_token\":\"test\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		return result.getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
	}

	private String createGuestCookie() throws Exception {
		String cookie = createGuestCookieWithoutConsent();
		mockMvc.perform(post("/v1/consent")
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, cookie))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"age_confirmed_18\":true}"))
			.andExpect(status().isCreated());
		return cookie;
	}

	private String signUpUserWithoutConsent() throws Exception {
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

	private String signUpUserWithConsent() throws Exception {
		String accessToken = signUpUserWithoutConsent();
		mockMvc.perform(post("/v1/consent")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"age_confirmed_18\":true}"))
			.andExpect(status().isCreated());
		return accessToken;
	}

	private byte[] validPdf() throws Exception {
		try (PDDocument document = new PDDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.save(output);
			return output.toByteArray();
		}
	}

	private byte[] validDocx() throws Exception {
		try (XWPFDocument document = new XWPFDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText("Resume experience");
			document.write(output);
			return output.toByteArray();
		}
	}
}
