package org.example.noneplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NonePlaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NonePlaceApplication.class, args);
    }

}
