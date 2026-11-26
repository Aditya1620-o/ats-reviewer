package com.atsreviewer.backend.storage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;

@Configuration
public class ImageKitStorageConfiguration {
	@Bean
	ImageKitClient imageKitClient(
			@Value("${app.storage.imagekit.private-key}") String privateKey,
			@Value("${app.storage.imagekit.url-endpoint}") String urlEndpoint) {
		if (privateKey.isBlank()) {
			throw new IllegalStateException("Set IMAGEKIT_PRIVATE_KEY before starting the application.");
		}
		URI endpoint = URI.create(urlEndpoint);
		if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
			throw new IllegalStateException("IMAGEKIT_URL_ENDPOINT must be an HTTPS URL.");
		}
		return ImageKitOkHttpClient.builder()
				.privateKey(privateKey)
				.build();
	}

	@Bean
	RestClient imageKitApiRestClient(@Value("${app.storage.imagekit.private-key}") String privateKey) {
		return RestClient.builder()
				.defaultHeaders(headers -> headers.setBasicAuth(privateKey, ""))
				.build();
	}

	@Bean
	RestClient imageKitContentRestClient() {
		return RestClient.builder().build();
	}
}
