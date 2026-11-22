package com.atsreviewer.backend.jobdescription;

import org.springframework.http.HttpStatus;

public class JobDescriptionException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public JobDescriptionException(HttpStatus status, String code, String message) {
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
