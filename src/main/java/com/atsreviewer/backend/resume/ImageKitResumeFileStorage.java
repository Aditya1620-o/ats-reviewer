package com.atsreviewer.backend.resume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class ImageKitResumeFileStorage implements ResumeFileStorage {
	private static final String UPLOAD_URL = "https://upload.imagekit.io/api/v1/files/upload";
	private static final String FILE_API_URL = "https://api.imagekit.io/v1/files/{fileId}";

	private final RestClient restClient;
	private final String folder;

	public ImageKitResumeFileStorage(
			RestClient imageKitRestClient,
			@Value("${app.storage.imagekit.folder}") String folder) {
		this.restClient = imageKitRestClient;
		this.folder = folder;
	}

	@Override
	public String store(String objectName, byte[] bytes, String contentType) {
		String fileName = objectName.replace('/', '_');
		ByteArrayResource fileResource = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return fileName;
			}
		};
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		parts.part("file", fileResource)
				.contentType(MediaType.parseMediaType(contentType));
		parts.part("fileName", fileName);
		parts.part("folder", folder);
		parts.part("isPrivateFile", "true");
		parts.part("useUniqueFileName", "false");

		ImageKitUploadResponse response = restClient.post()
				.uri(UPLOAD_URL)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(parts.build())
				.retrieve()
				.body(ImageKitUploadResponse.class);
		if (response == null || response.fileId() == null || response.fileId().isBlank()) {
			throw new RestClientException("ImageKit upload response did not contain a file ID.");
		}
		return response.fileId();
	}

	@Override
	public void delete(String fileId) {
		try {
			restClient.delete()
					.uri(FILE_API_URL, fileId)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() != 404) {
				throw exception;
			}
		}
	}

	private record ImageKitUploadResponse(String fileId) {
	}
}
