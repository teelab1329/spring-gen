package com.acme.orderservice.domain.exception;

/**
 * A request value failed validation. The message is human readable and is
 * rendered as the JSend {@code message}; the response carries no {@code data}.
 */
public class ValidationException extends RuntimeException {

	public ValidationException(String message) {
		super(message);
	}

}
