package com.acme.orderservice.domain.exception;

/**
 * A request value failed validation. Rendered as a JSend {@code fail} response.
 */
public class ValidationException extends RuntimeException {

	private final String field;

	public ValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
