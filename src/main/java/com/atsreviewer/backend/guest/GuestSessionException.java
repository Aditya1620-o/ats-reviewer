package com.atsreviewer.backend.guest;

import org.springframework.http.HttpStatus;

public class GuestSessionException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public GuestSessionException(HttpStatus status, String code, String message) {
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
