package br.com.fipe.sinc_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
class SincServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
