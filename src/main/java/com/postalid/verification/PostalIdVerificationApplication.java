package com.postalid.verification;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.config.WebUiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({PostalIdProperties.class, WebUiProperties.class})
public class PostalIdVerificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostalIdVerificationApplication.class, args);
    }
}
