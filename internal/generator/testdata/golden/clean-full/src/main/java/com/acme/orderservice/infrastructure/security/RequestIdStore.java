package com.acme.orderservice.infrastructure.security;

import java.time.Duration;

/** Records request ids so a replay within the signing window is rejected. */
public interface RequestIdStore {

	/**
	 * Marks the id as used.
	 * @return {@code true} when this call claimed the id, {@code false} when it
	 * was already present
	 */
	boolean markIfAbsent(String requestId, Duration ttl);

}
