package com.acme.orderservice.common.logging;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Masking rules applied to every log message and stack trace.
 *
 * <p>Masking happens in the Logback converters, not in application code, so it
 * also covers framework and library logs.</p>
 */
@ConfigurationProperties("app.logging.masking")
public record MaskingProperties(Boolean enabled, List<MaskingRule> rules) {

	public MaskingProperties {
		rules = (rules == null || rules.isEmpty()) ? MaskingRule.defaults() : List.copyOf(rules);
	}

	public boolean isEnabled() {
		return enabled == null || enabled;
	}

	public List<MaskingRule> maskingRules() {
		return rules;
	}

}
