package com.atsreviewer.backend.resume;

public interface ResumeFileStorage {
	String store(String objectName, byte[] bytes, String contentType);

	byte[] read(String fileId);

	void delete(String fileId);
}
