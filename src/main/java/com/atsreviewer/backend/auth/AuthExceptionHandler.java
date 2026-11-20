package com.atsreviewer.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.auth.AuthDtos.ErrorDetail;
import com.atsreviewer.backend.auth.AuthDtos.ErrorResponse;

@RestControllerAdvice
public class AuthExceptionHandler {
	@ExceptionHandler(AuthException.class)
	public ResponseEntity<ErrorResponse> handleAuthException(AuthException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}
}