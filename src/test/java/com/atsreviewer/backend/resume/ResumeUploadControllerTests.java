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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClientException;

import com.atsreviewer.backend.guest.GuestSessionService;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ResumeUploadControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper objectMapper;

	@MockitoBean
	private ResumeFileStorage fileStorage;

	@BeforeEach
	void configureImageKitStorage() {
		when(fileStorage.store(anyString(), any(byte[].class), anyString())).thenReturn("imagekit-file-id");
	}

	@Test
	void uploadsValidPdfForGuestSession() throws Exception {
		String guestCookie = createGuestCookie();
		MockMultipartFile file = new MockMultipartFile(
				"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf());

		mockMvc.perform(multipart("/v1/resumes")
				.file(file)
				.cookie(new jakarta.servlet.http.Cookie(GuestSessionService.COOKIE_NAME, guestCookie)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.resume_id").isNotEmpty())
			.andExpect(jsonPath("$.version_id").isNotEmpty())
			.andExpect(jsonPath("$.filename").value("resume.pdf"))
			.andExpect(jsonPath("$.mime_type").value(MediaType.APPLICATION_PDF_VALUE))
			.andExpect(jsonPath("$.size_bytes").isNumber());
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
	void reportsStorageUnavailableWhenImageKitWriteFails() throws Exception {
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
		String signupResponse = mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + UUID.randomUUID() + "@example.com\","
						+ "\"password\":\"long-enough-password\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String accessToken = objectMapper.readTree(signupResponse)
				.path("access_token")
				.stringValue();

		mockMvc.perform(multipart("/v1/resumes")
				.file(new MockMultipartFile(
						"file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, validPdf()))
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.resume_id").isNotEmpty());
	}

	private String createGuestCookie() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"captcha_token\":\"test\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		return result.getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
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
