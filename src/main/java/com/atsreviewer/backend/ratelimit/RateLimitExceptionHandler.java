package com.atsreviewer.backend.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.guest.GuestDtos.ErrorDetail;
import com.atsreviewer.backend.guest.GuestDtos.ErrorResponse;

@RestControllerAdvice
public class RateLimitExceptionHandler {
	@ExceptionHandler(RateLimitException.class)
	public ResponseEntity<ErrorResponse> handleRateLimitException(RateLimitException exception) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
		if (exception.retryAfter() != null) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(exception.retryAfter())));
		}
		return response.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}

	private long retryAfterSeconds(java.time.Duration retryAfter) {
		long seconds = retryAfter.getSeconds();
		return Math.max(1, retryAfter.getNano() == 0 ? seconds : seconds + 1);
	}
}
