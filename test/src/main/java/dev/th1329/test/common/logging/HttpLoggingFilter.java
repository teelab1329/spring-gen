package dev.th1329.test.common.logging;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs one line per request and one per response, including method, URL,
 * headers, body, status, and duration. Field names follow the OpenTelemetry
 * HTTP semantic conventions.
 *
 * <p>The trace id comes from the MDC, which Spring Boot fills from Micrometer
 * Tracing, so these lines share the {@code [traceId-spanId]} prefix of the rest
 * of the application logs. Disable with {@code app.http-logging.enabled=false}
 * or by raising the level of this logger.</p>
 */
public class HttpLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

	private static final String MASK = "[***]";

	private final HttpLoggingProperties properties;

	public HttpLoggingFilter(HttpLoggingProperties properties) {
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (properties.excludePaths().stream().anyMatch((pattern) -> pathMatches(pattern, path))) {
			return true;
		}
		// Buffering a stream would never finish.
		String accept = request.getHeader("Accept");
		return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		int limit = (int) Math.min(properties.maxBodySize().toBytes(), Integer.MAX_VALUE);
		// One extra byte tells "exactly at the limit" apart from "truncated".
		int cacheLimit = limit == Integer.MAX_VALUE ? limit : limit + 1;
		ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, cacheLimit);
		ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

		long start = System.nanoTime();
		try {
			chain.doFilter(cachedRequest, cachedResponse);
		}
		finally {
			long durationMillis = (System.nanoTime() - start) / 1_000_000;
			logRequest(cachedRequest);
			logResponse(cachedRequest, cachedResponse, durationMillis);
			// Without this the client receives an empty body.
			cachedResponse.copyBodyToResponse();
		}
	}

	private void logRequest(ContentCachingRequestWrapper request) {
		log.info("HTTP request http.request.method={} url.full={} headers={} body={}",
			request.getMethod(), urlFull(request), formatHeaders(requestHeaders(request)),
			body(request.getContentAsByteArray(), request.getContentType(), -1));
	}

	private void logResponse(HttpServletRequest request, ContentCachingResponseWrapper response, long durationMillis) {
		byte[] content = response.getContentAsByteArray();
		log.info("HTTP response http.request.method={} url.full={} http.response.status_code={} duration={}ms "
				+ "headers={} body={}",
			request.getMethod(), urlFull(request), response.getStatus(), durationMillis,
			formatHeaders(responseHeaders(response)),
			body(content, response.getContentType(), content.length));
	}

	private static String urlFull(HttpServletRequest request) {
		StringBuffer url = request.getRequestURL();
		String query = request.getQueryString();
		return query == null ? url.toString() : url + "?" + query;
	}

	private Map<String, List<String>> requestHeaders(HttpServletRequest request) {
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name, Collections.list(request.getHeaders(name)));
		}
		return headers;
	}

	private Map<String, List<String>> responseHeaders(HttpServletResponse response) {
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : response.getHeaderNames()) {
			headers.put(name, List.copyOf(response.getHeaders(name)));
		}
		return headers;
	}

	private String formatHeaders(Map<String, List<String>> headers) {
		List<String> entries = new ArrayList<>(headers.size());
		headers.forEach((name, values) -> entries.add(
			name.toLowerCase(Locale.ROOT) + "=" + (isMasked(name) ? MASK : values)));
		return entries.toString();
	}

	private boolean isMasked(String name) {
		return properties.maskedHeaders().stream().anyMatch((masked) -> masked.equalsIgnoreCase(name));
	}

	private String body(byte[] content, String contentType, long totalBytes) {
		if (content.length == 0) {
			return "";
		}
		MediaType mediaType = parseMediaType(contentType);
		if (mediaType == null || !isTextual(mediaType)) {
			return "[binary " + content.length + " bytes]";
		}

		int limit = (int) Math.min(properties.maxBodySize().toBytes(), Integer.MAX_VALUE);
		boolean truncated = content.length > limit || totalBytes > limit;
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

	private static MediaType parseMediaType(String contentType) {
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

	private static boolean isTextual(MediaType mediaType) {
		if ("text".equalsIgnoreCase(mediaType.getType())) {
			return true;
		}
		String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
		return subtype.equals("json") || subtype.endsWith("+json")
			|| subtype.equals("xml") || subtype.endsWith("+xml")
			|| subtype.equals("x-www-form-urlencoded");
	}

	/** Keeps one log entry on one line, even with multi-line or crafted bodies. */
	private static String escape(String text) {
		return text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
	}

	private static boolean pathMatches(String pattern, String path) {
		if (pattern.endsWith("/**")) {
			return path.startsWith(pattern.substring(0, pattern.length() - 3));
		}
		return pattern.equals(path);
	}

}
