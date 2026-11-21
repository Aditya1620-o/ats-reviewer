package com.atsreviewer.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.atsreviewer.backend.engine.extract.ResumeParser;

@SpringBootApplication
@EnableScheduling
public class AtsReviewerBackendApplication {

	@Bean
	ResumeParser resumeParser() {
		return new ResumeParser();
	}

	public static void main(String[] args) {
		SpringApplication.run(AtsReviewerBackendApplication.class, args);
	}

}
