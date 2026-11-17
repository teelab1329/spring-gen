package com.acme.orderservice.infrastructure.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Masks stack traces, e.g. a SQL exception echoing a masked value.
 *
 * <p>Registered as {@code %maskedEx} and selected through
 * {@code logging.exception-conversion-word}.</p>
 */
public class MaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

	@Override
	protected String throwableProxyToString(IThrowableProxy throwableProxy) {
		return LogMasker.get().mask(super.throwableProxyToString(throwableProxy));
	}

}
