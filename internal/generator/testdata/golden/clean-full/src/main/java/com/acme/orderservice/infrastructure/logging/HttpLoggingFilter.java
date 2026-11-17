package com.acme.orderservice.infrastructure.logging;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
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
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs {@code [HTTP-REQUEST]} before the request is handled and
 * {@code [HTTP-RESPONSE]} after it, so the log reads as request → business →
 * response. See the logging rule in AGENTS.md.
 *
 * <p>The trace id comes from the MDC, filled by Micrometer Tracing, so these
 * lines share the {@code [traceId-spanId]} prefix of the other application
 * logs. Disable with {@code app.http-logging.enabled=false} or by raising the
 * level of this logger.</p>
 */
public class HttpLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

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
		long start = System.nanoTime();
		ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

		HttpServletRequest effectiveRequest = request;
		String requestBody;
		MediaType mediaType = HttpLoggingSupport.parseMediaType(request.getContentType());

		if (isFormEncoded(mediaType)) {
			// Do not read the stream: Tomcat parses form bodies from its own stream.
			requestBody = formBody(request);
		}
		else if (mediaType == null || HttpLoggingSupport.isTextual(mediaType)) {
			BodyPeekingRequest peeking = new BodyPeekingRequest(request, limit());
			effectiveRequest = peeking;
			requestBody = HttpLoggingSupport.body(properties, peeking.loggable(), mediaType, -1, peeking.truncated());
		}
		else {
			requestBody = binaryBody(request);
		}

		// Logged before the handler runs, so business logs follow the request line.
		logRequest(request, requestBody);

		try {
			chain.doFilter(effectiveRequest, cachedResponse);
		}
		finally {
			long durationMillis = (System.nanoTime() - start) / 1_000_000;
			logResponse(request, cachedResponse, durationMillis);
			// Without this the client receives an empty body.
			cachedResponse.copyBodyToResponse();
		}
	}

	private void logRequest(HttpServletRequest request, String body) {
		log.info("{} method : {}, uri : {}, headers : {}, body : {}", LogType.HTTP_REQUEST,
			request.getMethod(), HttpLoggingSupport.uri(request),
			HttpLoggingSupport.headers(requestHeaders(request)), body);
	}

	private void logResponse(HttpServletRequest request, ContentCachingResponseWrapper response, long durationMillis) {
		byte[] content = response.getContentAsByteArray();
		log.info("{} method : {}, uri : {}, status : {}, duration : {}ms, headers : {}, body : {}",
			LogType.HTTP_RESPONSE, request.getMethod(), HttpLoggingSupport.uri(request), response.getStatus(),
			durationMillis, HttpLoggingSupport.headers(responseHeaders(response)),
			HttpLoggingSupport.body(properties, content, response.getContentType(), content.length));
	}

	private int limit() {
		return (int) Math.min(properties.maxBodySize().toBytes(), Integer.MAX_VALUE);
	}

	private static boolean isFormEncoded(MediaType mediaType) {
		return mediaType != null && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType);
	}

	private static String formBody(HttpServletRequest request) {
		Map<String, List<String>> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		request.getParameterMap().forEach((name, values) -> parameters.put(name, List.of(values)));
		return HttpLoggingSupport.headers(parameters);
	}

	private static String binaryBody(HttpServletRequest request) {
		long length = request.getContentLengthLong();
		return length >= 0 ? "[binary " + length + " bytes]" : "[binary]";
	}

	private static Map<String, List<String>> requestHeaders(HttpServletRequest request) {
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name, Collections.list(request.getHeaders(name)));
		}
		return headers;
	}

	private static Map<String, List<String>> responseHeaders(HttpServletResponse response) {
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : response.getHeaderNames()) {
			headers.put(name, List.copyOf(response.getHeaders(name)));
		}
		return headers;
	}

	private static boolean pathMatches(String pattern, String path) {
		if (pattern.endsWith("/**")) {
			return path.startsWith(pattern.substring(0, pattern.length() - 3));
		}
		return pattern.equals(path);
	}

}
