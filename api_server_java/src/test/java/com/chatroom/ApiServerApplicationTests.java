package com.chatroom;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires DynamoDB and Redis — run only in integration test environment")
class ApiServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
