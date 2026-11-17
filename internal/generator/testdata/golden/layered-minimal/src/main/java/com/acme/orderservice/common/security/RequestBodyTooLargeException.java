package com.acme.orderservice.common.security;

/** The request body exceeded {@code app.security.request-signature.max-body-size}. */
public class RequestBodyTooLargeException extends RuntimeException {

	public RequestBodyTooLargeException() {
		super("request body exceeds the signing limit");
	}

}
