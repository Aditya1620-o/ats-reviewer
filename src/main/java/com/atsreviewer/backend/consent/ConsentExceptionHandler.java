package com.atsreviewer.backend.consent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.consent.ConsentDtos.ErrorDetail;
import com.atsreviewer.backend.consent.ConsentDtos.ErrorResponse;

@RestControllerAdvice
public class ConsentExceptionHandler {
	@ExceptionHandler(ConsentException.class)
	public ResponseEntity<ErrorResponse> handleConsentException(ConsentException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}
}
