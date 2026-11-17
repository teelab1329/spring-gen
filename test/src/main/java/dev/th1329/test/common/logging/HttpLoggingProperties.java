package dev.th1329.test.common.logging;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration for HTTP request/response logging.
 *
 * <p>Headers named in {@code masked-headers} (case-insensitive) are logged as
 * {@code [***]}; bodies larger than {@code max-body-size} are truncated.</p>
 */
@ConfigurationProperties("app.http-logging")
public record HttpLoggingProperties(
		boolean enabled,
		DataSize maxBodySize,
		List<String> maskedHeaders,
		List<String> excludePaths) {

	public HttpLoggingProperties {
		if (maxBodySize == null) {
			maxBodySize = DataSize.ofKilobytes(10);
		}
		if (maskedHeaders == null) {
			maskedHeaders = List.of();
		}
		if (excludePaths == null) {
			excludePaths = List.of();
		}
	}

}
