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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class ImageKitResumeFileStorageTests {
	private static final String PRIVATE_KEY = "test-imagekit-private-key";
	private MockRestServiceServer server;
	private ImageKitResumeFileStorage storage;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient client = builder.defaultHeaders(headers -> headers.setBasicAuth(PRIVATE_KEY, "")).build();
		storage = new ImageKitResumeFileStorage(client, "test-resumes");
	}

	@Test
	void uploadsUsingGeneratedFilenameAndPrivateFileSetting() {
		String fileName = "resume-id_version-id.pdf";
		server.expect(requestTo("https://upload.imagekit.io/api/v1/files/upload"))
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
		server.verify();
	}

	@Test
	void deletesByImageKitFileId() {
		server.expect(requestTo("https://api.imagekit.io/v1/files/imagekit-file-id"))
				.andExpect(method(DELETE))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Basic "
						+ Base64.getEncoder().encodeToString((PRIVATE_KEY + ":").getBytes(StandardCharsets.UTF_8))))
				.andRespond(withNoContent());

		storage.delete("imagekit-file-id");
		server.verify();
	}

	@Test
	void treatsAnAlreadyDeletedFileAsSuccessfulCleanup() {
		server.expect(requestTo("https://api.imagekit.io/v1/files/imagekit-file-id"))
				.andExpect(method(DELETE))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		storage.delete("imagekit-file-id");
		server.verify();
	}
}
