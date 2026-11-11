package io.github.eswar7116.streamguard.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A small shop API instrumented with StreamGuard. Dashboard: http://localhost:8080/streamguard/ */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
