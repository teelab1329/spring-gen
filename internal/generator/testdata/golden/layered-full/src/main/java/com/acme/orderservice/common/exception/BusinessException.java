package com.acme.orderservice.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A business rule rejected the request. The caller chooses the HTTP status,
 * which must be a 4xx client error: JSend {@code fail} describes a request the
 * client can fix, while {@code error} covers server faults.
 *
 * <p>The message is human readable and the data is rendered as the JSend
 * {@code data} object, so keep it small and safe to expose. Pass {@code null}
 * when there is nothing to return.</p>
 */
public class BusinessException extends RuntimeException {

	private final HttpStatus status;
	private final Object data;

	public BusinessException(HttpStatus status, String message) {
		this(status, message, null);
	}

	public BusinessException(HttpStatus status, String message, Object data) {
		super(message);
		if (status == null || !status.is4xxClientError()) {
			throw new IllegalArgumentException("BusinessException requires a 4xx status, got " + status);
		}
		this.status = status;
		this.data = data;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public Object getData() {
		return data;
	}

}
