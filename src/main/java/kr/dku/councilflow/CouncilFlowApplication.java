package kr.dku.councilflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CouncilFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouncilFlowApplication.class, args);
    }
}
