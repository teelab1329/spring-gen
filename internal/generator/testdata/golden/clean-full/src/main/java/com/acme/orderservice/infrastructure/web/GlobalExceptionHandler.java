package com.acme.orderservice.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.acme.orderservice.domain.exception.BusinessException;
import com.acme.orderservice.domain.exception.ValidationException;
import com.acme.orderservice.infrastructure.web.response.JSendResponse;

/**
 * Maps errors onto JSend {@code fail} and {@code error} responses.
 *
 * <p>{@code message} is always human readable. Request-level failures carry no
 * {@code data}; business failures ({@link BusinessException}) carry business
 * data.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<JSendResponse<Void>> handleValidation(ValidationException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(JSendResponse.fail(exception.getMessage()));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<JSendResponse<Void>> handleMissingParameter(
			MissingServletRequestParameterException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(JSendResponse.fail("Missing required parameter '" + exception.getParameterName() + "'."));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<JSendResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(JSendResponse.fail("Invalid value for parameter '" + exception.getName() + "'."));
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<JSendResponse<Object>> handleBusiness(BusinessException exception) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
			.body(JSendResponse.fail(exception.getData(), exception.getMessage()));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<JSendResponse<Void>> handleNotFound(NoResourceFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(JSendResponse.fail("Resource not found: /" + exception.getResourcePath()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<JSendResponse<Void>> handleUnexpected(Exception exception) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(JSendResponse.error("Internal server error"));
	}

}
