package com.acme.orderservice.common.logging;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration for HTTP request/response logging.
 *
 * <p>Values are masked globally by the Logback converters (see
 * {@code app.logging.masking}); this filter logs raw headers and bodies and
 * relies on that. Bodies larger than {@code max-body-size} are truncated.</p>
 */
@ConfigurationProperties("app.http-logging")
public record HttpLoggingProperties(
		boolean enabled,
		DataSize maxBodySize,
		List<String> excludePaths) {

	public HttpLoggingProperties {
		if (maxBodySize == null) {
			maxBodySize = DataSize.ofKilobytes(10);
		}
		if (excludePaths == null) {
			excludePaths = List.of();
		}
	}

}
