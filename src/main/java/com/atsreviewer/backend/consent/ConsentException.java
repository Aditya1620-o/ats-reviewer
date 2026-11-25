package com.atsreviewer.backend.consent;

import org.springframework.http.HttpStatus;

public class ConsentException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public ConsentException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public ConsentException(HttpStatus status, String code, String message, Throwable cause) {
		super(message, cause);
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
