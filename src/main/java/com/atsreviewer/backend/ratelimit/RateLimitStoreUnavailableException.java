package com.atsreviewer.backend.ratelimit;

public class RateLimitStoreUnavailableException extends RuntimeException {
	public RateLimitStoreUnavailableException(Throwable cause) {
		super("The distributed rate-limit store is unavailable.", cause);
	}
}
