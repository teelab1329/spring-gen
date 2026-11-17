package com.acme.orderservice.infrastructure.logging;

/** How a masked value is rendered. */
public enum MaskingType {

	/** Replaced entirely with {@code ***}. */
	FULL,

	/** Keeps the first/last characters, stars in between. */
	PARTIAL,

	/** {@code john.doe@example.com -> j*******@example.com}. */
	EMAIL,

	/** {@code sha256:<first 16 hex>}. Short values can be brute-forced. */
	HASH

}
