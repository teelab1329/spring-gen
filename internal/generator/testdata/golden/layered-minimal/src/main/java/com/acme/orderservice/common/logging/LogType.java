package com.acme.orderservice.common.logging;

/**
 * Log tags, following the repository logging rule:
 * {@code [TYPE] message key : value, key : value}.
 *
 * <p>Add a tag here and to the rule in AGENTS.md before using it.</p>
 */
public final class LogType {

	public static final String HTTP_REQUEST = "[HTTP-REQUEST]";

	public static final String HTTP_RESPONSE = "[HTTP-RESPONSE]";

	public static final String BUSINESS_ERROR = "[BUSINESS-ERROR]";

	public static final String SYSTEM_ERROR = "[SYSTEM-ERROR]";

	public static final String RESTCLIENT_REQUEST = "[RESTCLIENT-REQUEST]";

	public static final String RESTCLIENT_RESPONSE = "[RESTCLIENT-RESPONSE]";

	public static final String RESTCLIENT_CONFIG = "[RESTCLIENT-CONFIG]";

	private LogType() {
	}

}
