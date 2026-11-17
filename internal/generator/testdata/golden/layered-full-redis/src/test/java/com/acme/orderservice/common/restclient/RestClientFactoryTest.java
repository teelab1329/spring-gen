package com.acme.orderservice.common.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import com.acme.orderservice.common.security.RequestSignatureProperties;
import com.acme.orderservice.common.security.RequestSigner;
import org.springframework.web.client.RestClient;

/**
 * Exercises the pool wiring against a local stub server: no network, no
 * external services.
 */
class RestClientFactoryTest {

	private HttpServer server;

	private SimpleMeterRegistry meterRegistry;

	private String baseUrl;

	private final AtomicInteger calls = new AtomicInteger();

	private volatile String lastRequestId;

	private volatile String lastTimestamp;

	private volatile String lastSignature;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/echo", (exchange) -> {
			calls.incrementAndGet();
			respond(exchange, 200);
		});
		server.createContext("/api/slow", (exchange) -> {
			calls.incrementAndGet();
			sleep(500);
			respond(exchange, 200);
		});
		server.createContext("/api/signed", (exchange) -> {
			calls.incrementAndGet();
			lastRequestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
			lastTimestamp = exchange.getRequestHeaders().getFirst("X-Request-Timestamp");
			lastSignature = exchange.getRequestHeaders().getFirst("X-Request-Signature");
			respond(exchange, 200);
		});
		server.createContext("/api/redirect", (exchange) -> {
			calls.incrementAndGet();
			exchange.getResponseHeaders().add("Location", "/api/echo");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();

		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		meterRegistry = new SimpleMeterRegistry();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
		meterRegistry.close();
	}

	@Test
	void wiresBaseUrlAndRegistersPoolMetrics() {
		RestClientFactory factory = factory(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
			Map.of("echo", client(baseUrl)));

		String body = factory.get("echo").get().uri("/api/echo").retrieve().body(String.class);

		assertThat(body).contains("\"ok\":true");
		assertThat(meterRegistry.getMeters())
			.anySatisfy((meter) -> assertThat(meter.getId().getTag("httpclient")).isEqualTo("echo"));
		assertThat(meterRegistry.getMeters().stream()
			.anyMatch((meter) -> meter.getId().getName().startsWith("httpcomponents.httpclient.pool"))).isTrue();
	}

	@Test
	void appliesReadTimeoutWithoutRetrying() {
		RestClientProperties.Client slow = new RestClientProperties.Client(baseUrl, null,
			Duration.ofMillis(100), null, null, null, null, null, null, null);
		RestClientFactory factory = factory(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
			Map.of("slow", slow));

		assertThatThrownBy(() -> factory.get("slow").get().uri("/api/slow").retrieve().body(String.class))
			.isInstanceOf(RuntimeException.class);
		assertThat(calls).hasValue(1);
	}

	@Test
	void doesNotFollowRedirects() {
		RestClientFactory factory = factory(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
			Map.of("redirect", client(baseUrl)));

		ResponseEntity<Void> response = factory.get("redirect")
			.get()
			.uri("/api/redirect")
			.retrieve()
			.toBodilessEntity();

		assertThat(response.getStatusCode().value()).isEqualTo(302);
		assertThat(calls).hasValue(1);
	}

	@Test
	void signsOutboundRequestsWhenConfigured() {
		RequestSigner signer = new RequestSigner(new RequestSignatureProperties(true, "test-secret",
			Duration.ofMinutes(5), null, java.util.List.of("/api/**"), java.util.List.of(), null));
		RestClientProperties.Client signing = new RestClientProperties.Client(baseUrl, null, null, null, null,
			null, null, null, null, true);
		RestClientFactory factory = new RestClientFactory(
			new RestClientProperties(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
				Map.of("signed", signing)),
			RestClient.builder(), meterRegistry, "test-app", signer);

		factory.get("signed").get().uri("/api/signed").retrieve().body(String.class);

		assertThat(lastRequestId).isNotBlank();
		assertThat(lastTimestamp).isNotBlank();
		assertThat(signer.matches(lastRequestId, lastTimestamp, new byte[0], lastSignature)).isTrue();
	}

	@Test
	void listsConfiguredClientsForUnknownNames() {
		RestClientFactory factory = factory(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
			Map.of("echo", client(baseUrl)));

		assertThatThrownBy(() -> factory.get("nope"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("echo");
	}

	@Test
	void failsFastOnMissingBaseUrl() {
		RestClientProperties.Client missing = new RestClientProperties.Client(null, null, null, null, null, null,
			null, null, null, null);

		assertThatThrownBy(() -> factory(defaults(Duration.ofSeconds(2), Duration.ofSeconds(5)),
			Map.of("bad", missing)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("base-url");
	}

	@Test
	void failsFastWhenPerRouteExceedsTotal() {
		RestClientProperties.Defaults defaults = new RestClientProperties.Defaults(Duration.ofSeconds(2),
			Duration.ofSeconds(5), Duration.ofSeconds(1), 5, 10, Duration.ofMinutes(1),
			Duration.ofSeconds(1), Duration.ofSeconds(5));

		assertThatThrownBy(() -> factory(defaults, Map.of("echo", client(baseUrl))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cannot exceed");
	}

	private RestClientFactory factory(RestClientProperties.Defaults defaults,
			Map<String, RestClientProperties.Client> clients) {
		return new RestClientFactory(new RestClientProperties(defaults, clients), RestClient.builder(),
			meterRegistry, "test-app", null);
	}

	private static RestClientProperties.Defaults defaults(Duration connect, Duration read) {
		return new RestClientProperties.Defaults(connect, read, Duration.ofSeconds(1), 10, 5,
			Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofSeconds(5));
	}

	private static RestClientProperties.Client client(String baseUrl) {
		return new RestClientProperties.Client(baseUrl, null, null, null, null, null, null, null, null, null);
	}

	private static void respond(HttpExchange exchange, int status) throws IOException {
		byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
