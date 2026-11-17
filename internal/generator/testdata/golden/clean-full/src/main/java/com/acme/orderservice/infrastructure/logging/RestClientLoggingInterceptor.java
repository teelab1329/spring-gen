package com.acme.orderservice.infrastructure.logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

/**
 * Logs outgoing calls as {@code [RESTCLIENT-REQUEST]} and
 * {@code [RESTCLIENT-RESPONSE]}.
 *
 * <p>Registered on the {@code RestClient.Builder} and {@code RestTemplate}
 * provided by Spring Boot. Clients created by hand bypass it.</p>
 */
public class RestClientLoggingInterceptor implements ClientHttpRequestInterceptor {

	private static final Logger log = LoggerFactory.getLogger(RestClientLoggingInterceptor.class);

	private final HttpLoggingProperties properties;

	public RestClientLoggingInterceptor(HttpLoggingProperties properties) {
		this.properties = properties;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		log.info("{} method : {}, uri : {}, headers : {}, body : {}", LogType.RESTCLIENT_REQUEST,
			request.getMethod(), request.getURI(),
			HttpLoggingSupport.headers(request.getHeaders()),
			HttpLoggingSupport.body(properties, body, request.getHeaders().getContentType(), body.length));

		long start = System.nanoTime();
		ClientHttpResponse response = execution.execute(request, body);
		long durationMillis = (System.nanoTime() - start) / 1_000_000;

		// The body can only be read once; buffer it and hand the caller a copy.
		byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
		log.info("{} method : {}, uri : {}, status : {}, duration : {}ms, headers : {}, body : {}",
			LogType.RESTCLIENT_RESPONSE, request.getMethod(), request.getURI(), statusCode(response),
			durationMillis, HttpLoggingSupport.headers(response.getHeaders()),
			HttpLoggingSupport.body(properties, responseBody, response.getHeaders().getContentType(),
				responseBody.length));

		return new BufferedResponse(response, responseBody);
	}

	private static int statusCode(ClientHttpResponse response) throws IOException {
		HttpStatusCode status = response.getStatusCode();
		return status.value();
	}

	private static final class BufferedResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;

		private final byte[] body;

		private BufferedResponse(ClientHttpResponse delegate, byte[] body) {
			this.delegate = delegate;
			this.body = body;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return delegate.getStatusText();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public InputStream getBody() {
			return new ByteArrayInputStream(body);
		}

		@Override
		public HttpHeaders getHeaders() {
			return delegate.getHeaders();
		}

	}

}
