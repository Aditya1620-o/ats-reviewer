package com.atsreviewer.backend.analysis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.guest.GuestDtos.ErrorDetail;
import com.atsreviewer.backend.guest.GuestDtos.ErrorResponse;

@RestControllerAdvice
public class AnalysisExceptionHandler {
	@ExceptionHandler(AnalysisException.class)
	public ResponseEntity<ErrorResponse> handleAnalysisException(AnalysisException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}
}
