package com.atsreviewer.backend.ratelimit;

import java.time.Duration;

import org.springframework.http.HttpStatus;

public class RateLimitException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	private final Duration retryAfter;

	public RateLimitException(HttpStatus status, String code, String message, Duration retryAfter) {
		super(message);
		this.status = status;
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
