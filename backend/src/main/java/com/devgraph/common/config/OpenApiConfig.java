package com.devgraph.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI devGraphOpenApi() {
		return new OpenAPI().info(new Info()
				.title("DevGraph API")
				.description("설계서 §14 API Architecture를 참고하는 자동 생성 schema. 행위 규칙은 설계서가 우선한다.")
				.version("v0"));
	}
}
