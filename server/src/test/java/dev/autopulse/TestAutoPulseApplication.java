package dev.autopulse;

import org.springframework.boot.SpringApplication;

public class TestAutoPulseApplication {

	public static void main(String[] args) {
		SpringApplication.from(AutoPulseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
