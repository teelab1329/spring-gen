package com.acme.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.security.request-signature.secret=test-secret")
class OrderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
