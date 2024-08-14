package dev.tylercash.event;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationProperties
public class TylerBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TylerBotApplication.class, args);
    }
}
