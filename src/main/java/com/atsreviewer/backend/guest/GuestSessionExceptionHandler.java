package com.atsreviewer.backend.guest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.guest.GuestDtos.ErrorDetail;
import com.atsreviewer.backend.guest.GuestDtos.ErrorResponse;

@RestControllerAdvice
public class GuestSessionExceptionHandler {
	@ExceptionHandler(GuestSessionException.class)
	public ResponseEntity<ErrorResponse> handleGuestSessionException(GuestSessionException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}
}
