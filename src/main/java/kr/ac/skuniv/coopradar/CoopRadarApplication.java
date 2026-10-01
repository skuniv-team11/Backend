package kr.ac.skuniv.coopradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoopRadarApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoopRadarApplication.class, args);
    }
}
