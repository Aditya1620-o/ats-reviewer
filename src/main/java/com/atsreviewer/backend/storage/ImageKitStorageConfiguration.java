package com.atsreviewer.backend.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ImageKitStorageConfiguration {
	@Bean
	RestClient imageKitRestClient(@Value("${app.storage.imagekit.private-key}") String privateKey) {
		if (privateKey.isBlank()) {
			throw new IllegalStateException("Set IMAGEKIT_PRIVATE_KEY before starting the application.");
		}
		return RestClient.builder()
				.defaultHeaders(headers -> headers.setBasicAuth(privateKey, ""))
				.build();
	}
}
