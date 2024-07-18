package dev.tylercash.event;

import org.springframework.boot.SpringApplication;

public class TestEventBotApplication {

	public static void main(String[] args) {
		SpringApplication.from(EventBotApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
