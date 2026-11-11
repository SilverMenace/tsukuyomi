package dev.tsukuyomi;

import org.springframework.boot.SpringApplication;

public class TestTsukuyomiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TsukuyomiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
