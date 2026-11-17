package com.acme.orderservice.infrastructure.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Masks sensitive values in any log text. Deliberately framework-free: the
 * Logback converters get it through a static holder, because Logback builds
 * them outside the Spring context.
 *
 * <p>Recognised shapes: {@code key=value}, {@code key: value}, {@code key:value},
 * JSON strings and numbers, our own {@code key : value} log rule, header maps
 * ({@code authorization=Bearer …}) and XML elements ({@code <password>…</password>}).</p>
 */
public final class LogMasker {

	private static final String FULL_MASK = "***";

	private static final String HASH_PREFIX = "sha256:";

	private static final int HASH_LENGTH = 16;

	/** Used before Spring configures the real rules. */
	private static volatile LogMasker instance = new LogMasker(true, MaskingRule.defaults());

	private final boolean enabled;

	private final List<Rule> rules;

	private LogMasker(boolean enabled, List<MaskingRule> rules) {
		this.enabled = enabled;
		this.rules = rules.stream().map(Rule::new).toList();
	}

	public static void configure(MaskingProperties properties) {
		instance = new LogMasker(properties.isEnabled(), properties.maskingRules());
	}

	public static LogMasker get() {
		return instance;
	}

	public String mask(String text) {
		if (!enabled || text == null || text.isEmpty()) {
			return text;
		}
		String masked = text;
		for (Rule rule : rules) {
			masked = rule.apply(masked);
		}
		return masked;
	}

	private static final class Rule {

		private final MaskingType type;

		private final int keepStart;

		private final int keepEnd;

		private final Pattern keyValue;

		private final Pattern xml;

		private final List<String> lowerCaseKeys;

		private Rule(MaskingRule rule) {
			this.type = rule.type();
			this.keepStart = rule.keepStart();
			this.keepEnd = rule.keepEnd();
			this.lowerCaseKeys = rule.keys().stream().map((key) -> key.toLowerCase(Locale.ROOT)).toList();

			String alternatives = rule.keys().stream()
				.map(Pattern::quote)
				.collect(Collectors.joining("|"));
			if (alternatives.isEmpty()) {
				this.keyValue = null;
				this.xml = null;
				return;
			}

			// key + separator, then a quoted value or a bare value. A bare value may
			// contain spaces ("Bearer abc") but stops at a delimiter or before the
			// next key=value pair.
			this.keyValue = Pattern.compile("(?i)(?<![\\w-])([\"']?(" + alternatives + ")[\"']?\\s*[:=]\\s*)"
				+ "(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'"
				+ "|[^,;&}\\])]+?(?=\\s+[\\w.\"'-]+\\s*[:=]|[,;&}\\])]|$))");
			this.xml = Pattern.compile("(?i)(<(" + alternatives + ")>)([^<]*)(</(?:"
				+ alternatives + ")>)");
		}

		private String apply(String text) {
			if (keyValue == null || !mentionsKey(text)) {
				return text;
			}
			return replaceXml(replaceKeyValues(text));
		}

		private boolean mentionsKey(String text) {
			String lower = text.toLowerCase(Locale.ROOT);
			for (String key : lowerCaseKeys) {
				if (lower.contains(key)) {
					return true;
				}
			}
			return false;
		}

		private String replaceKeyValues(String text) {
			Matcher matcher = keyValue.matcher(text);
			StringBuilder out = new StringBuilder(text.length());
			while (matcher.find()) {
				String raw = matcher.group(3);
				String masked = maskValue(unquote(raw));
				matcher.appendReplacement(out,
					Matcher.quoteReplacement(matcher.group(1) + requote(raw, masked)));
			}
			matcher.appendTail(out);
			return out.toString();
		}

		private String replaceXml(String text) {
			if (text.indexOf('<') < 0) {
				return text;
			}
			Matcher matcher = xml.matcher(text);
			StringBuilder out = new StringBuilder(text.length());
			while (matcher.find()) {
				matcher.appendReplacement(out,
					Matcher.quoteReplacement(matcher.group(1) + maskValue(matcher.group(3)) + matcher.group(4)));
			}
			matcher.appendTail(out);
			return out.toString();
		}

		private String maskValue(String value) {
			if (value.isEmpty()) {
				return value;
			}
			return switch (type) {
				case FULL -> FULL_MASK;
				case PARTIAL -> partial(value);
				case EMAIL -> email(value);
				case HASH -> HASH_PREFIX + sha256(value);
			};
		}

		private String partial(String value) {
			if (value.length() <= keepStart + keepEnd) {
				return FULL_MASK;
			}
			int stars = value.length() - keepStart - keepEnd;
			return value.substring(0, keepStart)
				+ "*".repeat(stars)
				+ value.substring(value.length() - keepEnd);
		}

		private String email(String value) {
			int at = value.indexOf('@');
			if (at <= 0 || at == value.length() - 1) {
				return FULL_MASK;
			}
			String local = value.substring(0, at);
			String domain = value.substring(at);
			return local.charAt(0) + "*".repeat(Math.max(1, local.length() - 1)) + domain;
		}

	}

	private static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' || first == '\'') && first == last) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	private static String requote(String raw, String masked) {
		if (!raw.isEmpty() && raw.charAt(0) == '"') {
			return "\"" + masked + "\"";
		}
		if (!raw.isEmpty() && raw.charAt(0) == '\'') {
			return "'" + masked + "'";
		}
		return masked;
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			String hex = HexFormat.of().formatHex(hash);
			return hex.substring(0, Math.min(HASH_LENGTH, hex.length()));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is required by every JRE; fail loudly rather than leak.
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
