package com.atsreviewer.backend.resume;

public interface ResumeFileStorage {
	String store(String objectName, byte[] bytes, String contentType);

	void delete(String fileId);
}
