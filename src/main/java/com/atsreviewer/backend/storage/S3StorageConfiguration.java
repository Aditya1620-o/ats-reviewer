package com.atsreviewer.backend.storage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class S3StorageConfiguration {
	@Bean
	S3Client s3Client(
			@Value("${app.storage.s3.endpoint:}") String endpoint,
			@Value("${app.storage.s3.region}") String region,
			@Value("${app.storage.s3.access-key:}") String accessKey,
			@Value("${app.storage.s3.secret-key:}") String secretKey) {
		if (accessKey.isBlank() != secretKey.isBlank()) {
			throw new IllegalStateException("Configure both S3_ACCESS_KEY and S3_SECRET_KEY, or neither.");
		}

		AwsCredentialsProvider credentialsProvider = accessKey.isBlank()
				? DefaultCredentialsProvider.create()
				: StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

		var builder = S3Client.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider)
				.serviceConfiguration(S3Configuration.builder()
						.pathStyleAccessEnabled(true)
						.build());
		if (!endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint));
		}
		return builder.build();
	}
}
