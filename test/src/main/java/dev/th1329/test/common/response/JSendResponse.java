package dev.th1329.test.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSend response envelope.
 *
 * <p>{@code message} carries human-readable text. {@code data} only carries
 * business information (for example the resource that failed); request-level
 * failures leave it empty.</p>
 *
 * @see <a href="https://github.com/omniti-labs/jsend">JSend specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JSendResponse<T>(String status, T data, String message) {

	public static <T> JSendResponse<T> success(T data) {
		return new JSendResponse<>("success", data, null);
	}

	/** Request-level failure: human-readable message, no business data. */
	public static JSendResponse<Void> fail(String message) {
		return new JSendResponse<>("fail", null, message);
	}

	/** Business failure: human-readable message plus business data. */
	public static <T> JSendResponse<T> fail(T data, String message) {
		return new JSendResponse<>("fail", data, message);
	}

	public static JSendResponse<Void> error(String message) {
		return new JSendResponse<>("error", null, message);
	}

}
