package com.acme.orderservice.infrastructure.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acme.orderservice.infrastructure.logging.LogType;
import com.acme.orderservice.infrastructure.web.response.JSendResponse;

/**
 * Validates {@code X-Request-Id}, {@code X-Request-Timestamp} and
 * {@code X-Request-Signature} in that order, then (optionally) rejects
 * duplicate request ids.
 *
 * <p>Order matters: the id is stored only after the request is proven
 * authentic, so unauthenticated traffic cannot burn a real client's id.</p>
 */
public class RequestSignatureFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	public static final String TIMESTAMP_HEADER = "X-Request-Timestamp";

	public static final String SIGNATURE_HEADER = "X-Request-Signature";

	private static final Logger log = LoggerFactory.getLogger(RequestSignatureFilter.class);

	private static final String REQUEST_ID_PATTERN = "[A-Za-z0-9-]{1,64}";

	private final RequestSignatureProperties properties;

	private final RequestSigner signer;

	private final RequestIdStore requestIdStore;

	private final ObjectMapper objectMapper;

	private final Clock clock;

	public RequestSignatureFilter(RequestSignatureProperties properties, RequestSigner signer,
			RequestIdStore requestIdStore, ObjectMapper objectMapper, Clock clock) {
		this.properties = properties;
		this.signer = signer;
		this.requestIdStore = requestIdStore;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (properties.excludePaths().stream().anyMatch((pattern) -> pathMatches(pattern, path))) {
			return true;
		}
		return properties.includePaths().stream().noneMatch((pattern) -> pathMatches(pattern, path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		String requestId = request.getHeader(REQUEST_ID_HEADER);

		if (!StringUtils.hasText(requestId)) {
			reject(response, HttpStatus.UNAUTHORIZED, "Missing header X-Request-Id.", uri, requestId);
			return;
		}
		if (!requestId.matches(REQUEST_ID_PATTERN)) {
			reject(response, HttpStatus.UNAUTHORIZED, "Invalid request id format.", uri, requestId);
			return;
		}

		String timestampText = request.getHeader(TIMESTAMP_HEADER);
		if (!StringUtils.hasText(timestampText)) {
			reject(response, HttpStatus.UNAUTHORIZED, "Missing header X-Request-Timestamp.", uri, requestId);
			return;
		}

		long timestamp;
		try {
			timestamp = Long.parseLong(timestampText);
		}
		catch (NumberFormatException ex) {
			reject(response, HttpStatus.UNAUTHORIZED, "Invalid header X-Request-Timestamp.", uri, requestId);
			return;
		}

		long maxSkew = properties.maxClockSkew().toMillis();
		if (Math.abs(clock.millis() - timestamp) > maxSkew) {
			reject(response, HttpStatus.UNAUTHORIZED, "Request timestamp is outside the allowed window.", uri,
				requestId);
			return;
		}

		String signature = request.getHeader(SIGNATURE_HEADER);
		if (!StringUtils.hasText(signature)) {
			reject(response, HttpStatus.UNAUTHORIZED, "Missing header X-Request-Signature.", uri, requestId);
			return;
		}

		int limit = (int) Math.min(properties.maxBodySize().toBytes(), Integer.MAX_VALUE);
		CachedBodyRequest cachedRequest;
		try {
			cachedRequest = new CachedBodyRequest(request, limit);
		}
		catch (RequestBodyTooLargeException ex) {
			reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large to sign.", uri, requestId);
			return;
		}

		if (!signer.matches(requestId, timestampText, cachedRequest.body(), signature)) {
			reject(response, HttpStatus.UNAUTHORIZED, "Invalid request signature.", uri, requestId);
			return;
		}

		if (properties.requestIdCheck().isEnabled()) {
			Duration ttl = properties.maxClockSkew().multipliedBy(2);
			try {
				if (!requestIdStore.markIfAbsent(requestId, ttl)) {
					reject(response, HttpStatus.CONFLICT, "Duplicate request id.", uri, requestId);
					return;
				}
			}
			catch (RuntimeException ex) {
				log.error("{} Request id store unavailable. requestId : {}, uri : {}", LogType.SECURITY_ERROR,
					requestId, uri, ex);
				write(response, HttpStatus.SERVICE_UNAVAILABLE, "Request id store unavailable.");
				return;
			}
		}

		chain.doFilter(cachedRequest, response);
	}

	private void reject(HttpServletResponse response, HttpStatus status, String message, String uri, String requestId)
			throws IOException {
		log.warn("{} {} requestId : {}, uri : {}", LogType.SECURITY_ERROR, message,
			StringUtils.hasText(requestId) ? requestId : "-", uri);
		write(response, status, message);
	}

	private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), JSendResponse.fail(message));
	}

	private static boolean pathMatches(String pattern, String path) {
		if (pattern.endsWith("/**")) {
			return path.startsWith(pattern.substring(0, pattern.length() - 3));
		}
		return pattern.equals(path);
	}

}
