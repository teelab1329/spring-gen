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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs one {@code [HTTP-REQUEST]} and one {@code [HTTP-RESPONSE]} line per
 * request. See the logging rule in AGENTS.md.
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
		log.info("{} method : {}, uri : {}, headers : {}, body : {}", LogType.HTTP_REQUEST,
			request.getMethod(), HttpLoggingSupport.uri(request),
			HttpLoggingSupport.headers(properties, requestHeaders(request)),
			HttpLoggingSupport.body(properties, request.getContentAsByteArray(), request.getContentType(), -1));
	}

	private void logResponse(HttpServletRequest request, ContentCachingResponseWrapper response, long durationMillis) {
		byte[] content = response.getContentAsByteArray();
		log.info("{} method : {}, uri : {}, status : {}, duration : {}ms, headers : {}, body : {}",
			LogType.HTTP_RESPONSE, request.getMethod(), HttpLoggingSupport.uri(request), response.getStatus(),
			durationMillis, HttpLoggingSupport.headers(properties, responseHeaders(response)),
			HttpLoggingSupport.body(properties, content, response.getContentType(), content.length));
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
