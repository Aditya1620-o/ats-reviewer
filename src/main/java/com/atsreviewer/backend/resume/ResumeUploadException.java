package com.atsreviewer.backend.resume;

import org.springframework.http.HttpStatus;

public class ResumeUploadException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public ResumeUploadException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
