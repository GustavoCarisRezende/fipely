package br.com.fipe.sinc_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import br.com.fipe.sinc_service.service.FipeClientProperties;

@SpringBootApplication
@EnableConfigurationProperties(FipeClientProperties.class)
public class SincServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SincServiceApplication.class, args);
	}

}
