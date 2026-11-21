package com.atsreviewer.backend.resume;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.atsreviewer.backend.guest.GuestDtos.ErrorDetail;
import com.atsreviewer.backend.guest.GuestDtos.ErrorResponse;

@RestControllerAdvice
public class ResumeUploadExceptionHandler {
	@ExceptionHandler(ResumeUploadException.class)
	public ResponseEntity<ErrorResponse> handleResumeUploadException(ResumeUploadException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ErrorResponse(new ErrorDetail(exception.code(), exception.getMessage())));
	}
}
