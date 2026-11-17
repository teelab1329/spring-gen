package com.acme.orderservice.infrastructure.restclient;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.acme.orderservice.infrastructure.logging.LogType;

/**
 * Builds one Apache HttpClient 5 connection pool per configured downstream
 * service.
 *
 * <p>Clients are created from Spring Boot's provided {@link RestClient.Builder}
 * clone, so trace propagation and the HTTP logging interceptor still apply.
 * Automatic retries and redirect following are disabled on purpose.</p>
 */
@Component
public class RestClientFactory implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RestClientFactory.class);

	private final Map<String, RestClient> clients = new LinkedHashMap<>();

	private final Map<String, CloseableHttpClient> httpClients = new LinkedHashMap<>();

	@Autowired
	public RestClientFactory(RestClientProperties properties, RestClient.Builder builder,
			ObjectProvider<MeterRegistry> meterRegistries,
			@Value("${spring.application.name:spring-gen}") String applicationName) {
		this(properties, builder, meterRegistries.getIfAvailable(), applicationName);
	}

	RestClientFactory(RestClientProperties properties, RestClient.Builder builder, MeterRegistry meterRegistry,
			String applicationName) {
		properties.clients().forEach((name, client) -> {
			String baseUrl = client.baseUrl();
			if (baseUrl == null || baseUrl.isBlank()) {
				throw new IllegalStateException(
					"app.rest-clients.clients." + name + ".base-url is required");
			}

			Settings settings = resolve(properties.defaults(), client, name);

			PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
				.setMaxConnTotal(settings.maxConnections())
				.setMaxConnPerRoute(settings.maxConnectionsPerRoute())
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.ofMilliseconds(settings.connectTimeout().toMillis()))
					.setSocketTimeout(Timeout.ofMilliseconds(settings.readTimeout().toMillis()))
					.setTimeToLive(TimeValue.ofMilliseconds(settings.timeToLive().toMillis()))
					.setValidateAfterInactivity(
						TimeValue.ofMilliseconds(settings.validateAfterInactivity().toMillis()))
					.build())
				.build();

			CloseableHttpClient httpClient = HttpClientBuilder.create()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(RequestConfig.custom()
					.setConnectionRequestTimeout(
						Timeout.ofMilliseconds(settings.connectionRequestTimeout().toMillis()))
					.build())
				.disableAutomaticRetries()
				.disableRedirectHandling()
				.evictIdleConnections(TimeValue.ofMilliseconds(settings.evictIdleAfter().toMillis()))
				.setUserAgent(applicationName + "-restclient")
				.build();

			if (meterRegistry != null) {
				new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, name)
					.bindTo(meterRegistry);
			}

			HttpComponentsClientHttpRequestFactory requestFactory =
				new HttpComponentsClientHttpRequestFactory(httpClient);

			clients.put(name, builder.clone()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, applicationName + "-restclient")
				.build());
			httpClients.put(name, httpClient);

			log.info("{} client : {}, baseUri : {}, maxConnections : {}, "
					+ "maxConnectionsPerRoute : {}, connectTimeout : {}ms, readTimeout : {}ms",
				LogType.RESTCLIENT_CONFIG, name, baseUrl, settings.maxConnections(),
				settings.maxConnectionsPerRoute(), settings.connectTimeout().toMillis(),
				settings.readTimeout().toMillis());
		});
	}

	public RestClient get(String name) {
		RestClient client = clients.get(name);
		if (client == null) {
			throw new IllegalArgumentException(
				"unknown REST client '" + name + "' (configured: " + clients.keySet() + ")");
		}
		return client;
	}

	public Set<String> names() {
		return Set.copyOf(clients.keySet());
	}

	@Override
	public void close() {
		httpClients.forEach((name, httpClient) -> {
			try {
				httpClient.close();
			}
			catch (IOException ex) {
				log.error("{} Failed to close the REST client '{}'.", LogType.SYSTEM_ERROR, name, ex);
			}
		});
	}

	private static Settings resolve(RestClientProperties.Defaults defaults, RestClientProperties.Client client,
			String name) {
		Settings settings = new Settings(
			firstNonNull(client.connectTimeout(), defaults.connectTimeout()),
			firstNonNull(client.readTimeout(), defaults.readTimeout()),
			firstNonNull(client.connectionRequestTimeout(), defaults.connectionRequestTimeout()),
			firstNonNull(client.maxConnections(), defaults.maxConnections()),
			firstNonNull(client.maxConnectionsPerRoute(), defaults.maxConnectionsPerRoute()),
			firstNonNull(client.timeToLive(), defaults.timeToLive()),
			firstNonNull(client.validateAfterInactivity(), defaults.validateAfterInactivity()),
			firstNonNull(client.evictIdleAfter(), defaults.evictIdleAfter()));

		String prefix = "app.rest-clients.clients." + name + ".";
		requirePositive(prefix + "connect-timeout", settings.connectTimeout());
		requirePositive(prefix + "read-timeout", settings.readTimeout());
		requirePositive(prefix + "connection-request-timeout", settings.connectionRequestTimeout());
		requirePositive(prefix + "time-to-live", settings.timeToLive());
		if (settings.maxConnections() < 1) {
			throw new IllegalStateException(prefix + "max-connections must be at least 1");
		}
		if (settings.maxConnectionsPerRoute() < 1) {
			throw new IllegalStateException(prefix + "max-connections-per-route must be at least 1");
		}
		if (settings.maxConnectionsPerRoute() > settings.maxConnections()) {
			throw new IllegalStateException(prefix + "max-connections-per-route cannot exceed max-connections");
		}
		return settings;
	}

	private static void requirePositive(String property, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(property + " must be positive");
		}
	}

	private static <T> T firstNonNull(T value, T fallback) {
		return value == null ? fallback : value;
	}

	private record Settings(Duration connectTimeout, Duration readTimeout, Duration connectionRequestTimeout,
			int maxConnections, int maxConnectionsPerRoute, Duration timeToLive,
			Duration validateAfterInactivity, Duration evictIdleAfter) {
	}

}
