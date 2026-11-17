package com.acme.orderservice.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LogMaskerTest {

	@Test
	void masksKeyValueInEveryFormat() {
		LogMasker masker = masker(new MaskingRule(MaskingType.FULL, List.of("password", "authorization"), 0, 0));

		assertThat(masker.mask("password=hunter2")).isEqualTo("password=***");
		assertThat(masker.mask("password: hunter2")).isEqualTo("password: ***");
		assertThat(masker.mask("password : hunter2, user : tee")).isEqualTo("password : ***, user : tee");
		assertThat(masker.mask("{\"password\":\"hunter2\",\"user\":\"tee\"}"))
			.isEqualTo("{\"password\":\"***\",\"user\":\"tee\"}");
		assertThat(masker.mask("<password>hunter2</password>")).isEqualTo("<password>***</password>");
		assertThat(masker.mask("headers : {authorization=Bearer abc, host=example}"))
			.isEqualTo("headers : {authorization=***, host=example}");
	}

	@Test
	void masksAdjacentPairsOnOneLine() {
		LogMasker.configure(new MaskingProperties(true, List.of(
			new MaskingRule(MaskingType.FULL, List.of("password"), 0, 0),
			new MaskingRule(MaskingType.EMAIL, List.of("email"), 0, 0))));

		assertThat(LogMasker.get().mask("password=hunter2 email=john.doe@example.com"))
			.isEqualTo("password=*** email=j*******@example.com");
	}

	@Test
	void ignoresKeysThatOnlyLookSimilar() {
		LogMasker masker = masker(new MaskingRule(MaskingType.FULL, List.of("token"), 0, 0));

		assertThat(masker.mask("tokenizer=keep")).isEqualTo("tokenizer=keep");
		assertThat(masker.mask("access_token=drop")).isEqualTo("access_token=drop");
		assertThat(masker.mask("TOKEN=drop")).isEqualTo("TOKEN=***");
	}

	@Test
	void appliesPartialMasking() {
		LogMasker masker = masker(new MaskingRule(MaskingType.PARTIAL,
			List.of("cardNumber"), 4, 4));

		assertThat(masker.mask("cardNumber=4111111111111111"))
			.isEqualTo("cardNumber=4111********1111");
		assertThat(masker.mask("cardNumber=\"4111111111111111\""))
			.isEqualTo("cardNumber=\"4111********1111\"");
		// Too short to keep both ends.
		assertThat(masker.mask("cardNumber=12345678")).isEqualTo("cardNumber=***");
	}

	@Test
	void appliesEmailMasking() {
		LogMasker masker = masker(new MaskingRule(MaskingType.EMAIL, List.of("email"), 0, 0));

		assertThat(masker.mask("email=john.doe@example.com")).isEqualTo("email=j*******@example.com");
		assertThat(masker.mask("email=not-an-email")).isEqualTo("email=***");
	}

	@Test
	void appliesHashMasking() {
		LogMasker masker = masker(new MaskingRule(MaskingType.HASH, List.of("phone"), 0, 0));

		// sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
		assertThat(masker.mask("phone=hello")).isEqualTo("phone=sha256:2cf24dba5fb0a30e");
	}

	@Test
	void leavesUnrelatedTextUntouched() {
		LogMasker masker = masker(new MaskingRule(MaskingType.FULL, List.of("password"), 0, 0));

		assertThat(masker.mask("Started application in 2.5 seconds"))
			.isEqualTo("Started application in 2.5 seconds");
		assertThat(masker.mask("")).isEmpty();
	}

	@Test
	void disabledMaskerIsANoOp() {
		LogMasker.configure(new MaskingProperties(false, MaskingRule.defaults()));

		assertThat(LogMasker.get().mask("password=hunter2")).isEqualTo("password=hunter2");
	}

	private static LogMasker masker(MaskingRule... rules) {
		LogMasker.configure(new MaskingProperties(true, List.of(rules)));
		return LogMasker.get();
	}

}
