package com.atsreviewer.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AtsReviewerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtsReviewerBackendApplication.class, args);
	}

}
