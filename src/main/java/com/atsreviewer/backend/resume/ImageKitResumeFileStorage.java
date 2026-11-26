package com.atsreviewer.backend.resume;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.imagekit.client.ImageKitClient;
import io.imagekit.models.SrcOptions;

@Service
public class ImageKitResumeFileStorage implements ResumeFileStorage {
	private static final String UPLOAD_URL = "https://upload.imagekit.io/api/v1/files/upload";
	private static final String FILE_API_URL = "https://api.imagekit.io/v1/files/{fileId}";
	private static final String FILE_DETAILS_API_URL = FILE_API_URL + "/details";
	private static final double DOWNLOAD_URL_LIFETIME_SECONDS = 60.0;

	private final RestClient apiRestClient;
	private final RestClient contentRestClient;
	private final ImageKitClient imageKitClient;
	private final String folder;
	private final String urlEndpoint;

	public ImageKitResumeFileStorage(
			RestClient imageKitApiRestClient,
			RestClient imageKitContentRestClient,
			ImageKitClient imageKitClient,
			@Value("${app.storage.imagekit.folder}") String folder,
			@Value("${app.storage.imagekit.url-endpoint}") String urlEndpoint) {
		this.apiRestClient = imageKitApiRestClient;
		this.contentRestClient = imageKitContentRestClient;
		this.imageKitClient = imageKitClient;
		this.folder = folder;
		this.urlEndpoint = urlEndpoint.endsWith("/")
				? urlEndpoint.substring(0, urlEndpoint.length() - 1)
				: urlEndpoint;
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

		ImageKitUploadResponse response = apiRestClient.post()
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
	public byte[] read(String fileId) {
		ImageKitFileDetails details = apiRestClient.get()
				.uri(FILE_DETAILS_API_URL, fileId)
				.retrieve()
				.body(ImageKitFileDetails.class);
		if (details == null || details.filePath() == null || details.filePath().isBlank()) {
			throw new RestClientException("ImageKit file details did not contain a file path.");
		}

		String signedUrl = imageKitClient.helper().buildUrl(
				SrcOptions.builder()
						.urlEndpoint(urlEndpoint)
						.src(details.filePath())
						.signed(true)
						.expiresIn(DOWNLOAD_URL_LIFETIME_SECONDS)
						.build());
		byte[] bytes = contentRestClient.get()
				.uri(URI.create(signedUrl))
				.retrieve()
				.body(byte[].class);
		if (bytes == null) {
			throw new RestClientException("ImageKit download response did not contain file content.");
		}
		return bytes;
	}

	@Override
	public void delete(String fileId) {
		try {
			apiRestClient.delete()
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

	private record ImageKitFileDetails(String filePath) {
	}
}
