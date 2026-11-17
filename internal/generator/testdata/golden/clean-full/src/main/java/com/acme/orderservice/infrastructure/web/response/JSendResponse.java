package com.acme.orderservice.infrastructure.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSend response envelope.
 *
 * @see <a href="https://github.com/omniti-labs/jsend">JSend specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JSendResponse<T>(String status, T data, String message) {

	public static <T> JSendResponse<T> success(T data) {
		return new JSendResponse<>("success", data, null);
	}

	public static <T> JSendResponse<T> fail(T data) {
		return new JSendResponse<>("fail", data, null);
	}

	public static JSendResponse<Void> error(String message) {
		return new JSendResponse<>("error", null, message);
	}

}
