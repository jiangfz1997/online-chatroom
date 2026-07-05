package com.chatroom.persist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PersistWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersistWorkerApplication.class, args);
    }
}
