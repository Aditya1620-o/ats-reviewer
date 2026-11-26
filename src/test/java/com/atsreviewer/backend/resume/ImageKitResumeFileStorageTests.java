package com.atsreviewer.backend.resume;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ImageKitResumeFileStorageTests {
	private static final String PRIVATE_KEY = "test-imagekit-private-key";
	private static final String URL_ENDPOINT = "https://ik.imagekit.io/test";

	private MockRestServiceServer apiServer;
	private MockRestServiceServer contentServer;
	private ImageKitResumeFileStorage storage;

	@BeforeEach
	void setUp() {
		RestClient.Builder apiBuilder = RestClient.builder();
		apiServer = MockRestServiceServer.bindTo(apiBuilder).build();
		RestClient apiClient = apiBuilder.defaultHeaders(headers -> headers.setBasicAuth(PRIVATE_KEY, "")).build();

		RestClient.Builder contentBuilder = RestClient.builder();
		contentServer = MockRestServiceServer.bindTo(contentBuilder).build();
		RestClient contentClient = contentBuilder.build();

		ImageKitClient imageKitClient = ImageKitOkHttpClient.builder()
				.privateKey(PRIVATE_KEY)
				.build();

		storage = new ImageKitResumeFileStorage(
				apiClient, contentClient, imageKitClient, "test-resumes", URL_ENDPOINT);
	}

	@Test
	void uploadsUsingGeneratedFilenameAndPrivateFileSetting() {
		String fileName = "resume-id_version-id.pdf";
		apiServer.expect(requestTo("https://upload.imagekit.io/api/v1/files/upload"))
				.andExpect(method(POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Basic "
						+ Base64.getEncoder().encodeToString((PRIVATE_KEY + ":").getBytes(StandardCharsets.UTF_8))))
				.andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
				.andExpect(content().string(allOf(
						containsString("name=\"fileName\""),
						containsString(fileName),
						containsString("name=\"folder\""),
						containsString("test-resumes"),
						containsString("name=\"isPrivateFile\""),
						containsString("\r\n\r\ntrue"))))
				.andRespond(withSuccess("{\"fileId\":\"imagekit-file-id\"}", MediaType.APPLICATION_JSON));

		assertEquals("imagekit-file-id", storage.store("resume-id/version-id.pdf", new byte[] {1, 2, 3},
				MediaType.APPLICATION_PDF_VALUE));
		apiServer.verify();
	}

	@Test
	void readsFileUsingSignedUrlFromFileDetails() {
		byte[] expected = "resume document".getBytes(StandardCharsets.UTF_8);
		apiServer.expect(requestTo("https://api.imagekit.io/v1/files/imagekit-file-id/details"))
				.andExpect(method(GET))
				.andRespond(withSuccess("{\"filePath\":\"/test-resumes/resume-id_version-id.pdf\"}",
						MediaType.APPLICATION_JSON));
		contentServer.expect(requestTo(startsWith(URL_ENDPOINT + "/test-resumes/resume-id_version-id.pdf")))
				.andExpect(method(GET))
				.andExpect(request -> {
					String query = request.getURI().getQuery();
					assertEquals(true, query != null && query.contains("ik-s="));
				})
				.andRespond(withSuccess(expected, MediaType.APPLICATION_PDF));

		assertArrayEquals(expected, storage.read("imagekit-file-id"));
		apiServer.verify();
		contentServer.verify();
	}

	@Test
	void deletesByImageKitFileId() {
		apiServer.expect(requestTo("https://api.imagekit.io/v1/files/imagekit-file-id"))
				.andExpect(method(DELETE))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Basic "
						+ Base64.getEncoder().encodeToString((PRIVATE_KEY + ":").getBytes(StandardCharsets.UTF_8))))
				.andRespond(withNoContent());

		storage.delete("imagekit-file-id");
		apiServer.verify();
	}

	@Test
	void treatsAnAlreadyDeletedFileAsSuccessfulCleanup() {
		apiServer.expect(requestTo("https://api.imagekit.io/v1/files/imagekit-file-id"))
				.andExpect(method(DELETE))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		storage.delete("imagekit-file-id");
		apiServer.verify();
	}
}
