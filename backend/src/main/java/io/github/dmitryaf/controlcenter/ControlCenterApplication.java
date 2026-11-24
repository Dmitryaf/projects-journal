package io.github.dmitryaf.controlcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class ControlCenterApplication {

    public static void main(String[] args) {
        String configured = System.getenv().getOrDefault("PORT", "4310").strip();
        int port = Integer.parseInt(configured);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("PORT должен быть от 1024 до 65535.");
        }
        SpringApplication.run(ControlCenterApplication.class,
                "--server.address=127.0.0.1", "--server.port=" + port);
    }

    @Bean
    ProjectFiles projectFiles(JsonMapper mapper) {
        return new ProjectFiles(mapper);
    }
}
