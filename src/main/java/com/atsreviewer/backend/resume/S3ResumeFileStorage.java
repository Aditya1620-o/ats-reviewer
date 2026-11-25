package com.atsreviewer.backend.resume;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3ResumeFileStorage {
	private final S3Client s3Client;
	private final String bucket;

	public S3ResumeFileStorage(
			S3Client s3Client,
			@Value("${app.storage.s3.bucket}") String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	public void store(String key, byte[] bytes, String contentType, String sha256) {
		PutObjectRequest request = PutObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.contentType(contentType)
				.contentLength((long) bytes.length)
				.metadata(Map.of("sha256", sha256))
				.build();
		s3Client.putObject(request, RequestBody.fromBytes(bytes));
	}

	public void delete(String key) {
		s3Client.deleteObject(DeleteObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build());
	}

	public byte[] read(String key) {
		return s3Client.getObjectAsBytes(GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build())
				.asByteArray();
	}
}
