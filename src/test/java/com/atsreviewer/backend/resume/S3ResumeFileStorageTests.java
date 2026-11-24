package com.atsreviewer.backend.resume;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ResumeFileStorageTests {
	@Test
	void readsTheRequestedObjectFromConfiguredBucket() {
		S3Client s3Client = mock(S3Client.class);
		byte[] expected = "resume document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected));
		S3ResumeFileStorage storage = new S3ResumeFileStorage(s3Client, "resume-bucket");

		byte[] actual = storage.read("resume-id/version-id.pdf");

		assertArrayEquals(expected, actual);
		org.mockito.ArgumentCaptor<GetObjectRequest> request =
				org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client).getObjectAsBytes(request.capture());
		assertEquals("resume-bucket", request.getValue().bucket());
		assertEquals("resume-id/version-id.pdf", request.getValue().key());
	}
}
