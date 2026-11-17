package com.acme.orderservice.domain.exception;

/**
 * A business rule rejected the request. The message is human readable and the
 * data is rendered as the JSend {@code data} object, so keep it small and safe
 * to expose.
 */
public class BusinessException extends RuntimeException {

	private final Object data;

	public BusinessException(String message, Object data) {
		super(message);
		this.data = data;
	}

	public Object getData() {
		return data;
	}

}
