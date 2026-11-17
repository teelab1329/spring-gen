package com.acme.orderservice.infrastructure.logging;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Rendering helpers shared by the HTTP logging filter and the REST client
 * interceptor. Masking is not applied here: the global Logback converters
 * mask the finished line.
 */
final class HttpLoggingSupport {

	private HttpLoggingSupport() {
	}

	/** Path plus query string, e.g. {@code /api/hello?name=Tee}. */
	static String uri(HttpServletRequest request) {
		String query = request.getQueryString();
		return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
	}

	static String headers(HttpHeaders headers) {
		Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.forEach(values::put);
		return headers(values);
	}

	/** Renders {@code {name=value, ...}} with repeated values joined by commas. */
	static String headers(Map<String, List<String>> headers) {
		Map<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		sorted.putAll(headers);

		List<String> entries = new ArrayList<>(sorted.size());
		sorted.forEach((name, values) -> entries.add(
			name.toLowerCase(Locale.ROOT) + "=" + String.join(", ", values)));
		return "{" + String.join(", ", entries) + "}";
	}

	static String body(HttpLoggingProperties properties, byte[] content, String contentType, long totalBytes) {
		return body(properties, content, parseMediaType(contentType), totalBytes, false);
	}

	static String body(HttpLoggingProperties properties, byte[] content, MediaType mediaType, long totalBytes) {
		return body(properties, content, mediaType, totalBytes, false);
	}

	/**
	 * Renders a body. {@code totalBytes} is the real size when known, or a
	 * negative value when the content was capped before rendering;
	 * {@code truncatedHint} marks a body the caller already truncated.
	 */
	static String body(HttpLoggingProperties properties, byte[] content, MediaType mediaType, long totalBytes,
			boolean truncatedHint) {
		if (content.length == 0 && !truncatedHint) {
			return "";
		}
		if (mediaType == null || !isTextual(mediaType)) {
			return "[binary " + content.length + " bytes]";
		}

		int limit = (int) Math.min(properties.maxBodySize().toBytes(), Integer.MAX_VALUE);
		boolean truncated = truncatedHint || content.length > limit || totalBytes > limit;
		byte[] visible = truncated ? Arrays.copyOf(content, Math.min(content.length, limit)) : content;
		Charset charset = mediaType.getCharset() == null ? StandardCharsets.UTF_8 : mediaType.getCharset();

		String text = escape(new String(visible, charset));
		if (!truncated) {
			return text;
		}
		if (totalBytes >= 0) {
			return text + "...[truncated, " + totalBytes + " bytes]";
		}
		return text + "...[truncated at " + limit + " bytes]";
	}

	static MediaType parseMediaType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return null;
		}
		try {
			return MediaType.parseMediaType(contentType);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	static boolean isTextual(MediaType mediaType) {
		if ("text".equalsIgnoreCase(mediaType.getType())) {
			return true;
		}
		String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
		return subtype.equals("json") || subtype.endsWith("+json")
			|| subtype.equals("xml") || subtype.endsWith("+xml")
			|| subtype.equals("x-www-form-urlencoded");
	}

	/** Keeps one log entry on one line, even with crafted bodies. */
	static String escape(String text) {
		return text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
	}

}
