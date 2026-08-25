package com.andre.virtualcard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VirtualCardIssuancePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualCardIssuancePlatformApplication.class, args);
    }

}
