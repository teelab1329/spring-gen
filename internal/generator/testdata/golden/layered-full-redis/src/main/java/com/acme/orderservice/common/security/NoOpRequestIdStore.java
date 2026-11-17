package com.acme.orderservice.common.security;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Used when {@code app.security.request-signature.request-id-check.enabled} is
 * false: signatures and timestamps still protect requests, replays within the
 * clock-skew window are not detected.
 */
@Component
@ConditionalOnProperty(prefix = "app.security.request-signature.request-id-check", name = "enabled",
		havingValue = "false", matchIfMissing = true)
public class NoOpRequestIdStore implements RequestIdStore {

	@Override
	public boolean markIfAbsent(String requestId, Duration ttl) {
		return true;
	}

}
