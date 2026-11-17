package com.acme.orderservice.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRequestIdStoreTest {

	private static final Duration TTL = Duration.ofMinutes(10);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	@Test
	void claimsANewRequestId() {
		given(redis.opsForValue()).willReturn(values);
		given(values.setIfAbsent("req-id:abc", "1", TTL)).willReturn(true);

		assertThat(store().markIfAbsent("abc", TTL)).isTrue();
	}

	@Test
	void detectsADuplicateRequestId() {
		given(redis.opsForValue()).willReturn(values);
		given(values.setIfAbsent("req-id:abc", "1", TTL)).willReturn(false);

		assertThat(store().markIfAbsent("abc", TTL)).isFalse();
	}

	@Test
	void propagatesStoreFailures() {
		given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("redis down"));

		assertThatThrownBy(() -> store().markIfAbsent("abc", TTL))
			.isInstanceOf(RedisConnectionFailureException.class);
	}

	private RedisRequestIdStore store() {
		return new RedisRequestIdStore(redis, new RequestSignatureProperties(true, "test-secret",
			Duration.ofMinutes(5), null, List.of("/api/**"), List.of(),
			new RequestSignatureProperties.RequestIdCheck(true, null)));
	}

}
