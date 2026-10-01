package com.devgraph.auth.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 운영자용 CLI 진입점(§17.8). 웹 서버 없이 한 번 실행하고 종료한다:
 * <pre>
 * java -jar devgraph.jar --spring.main.web-application-type=none --admin.command=force-password-reset --admin.email=user@example.com
 * </pre>
 * 임시 비밀번호는 표준 출력에만 한 번 쓴다(로그·채팅으로 전달하지 않는다).
 */
@Component
@ConditionalOnProperty(name = "admin.command")
public class AdminCommandRunner implements ApplicationRunner {

	private final AdminAccountService adminAccountService;
	private final ConfigurableApplicationContext context;

	public AdminCommandRunner(AdminAccountService adminAccountService, ConfigurableApplicationContext context) {
		this.adminAccountService = adminAccountService;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		String command = first(args, "admin.command");
		int exitCode = 0;
		if ("force-password-reset".equals(command)) {
			String email = first(args, "admin.email");
			if (email == null || email.isBlank()) {
				System.err.println("--admin.email=<email> is required");
				exitCode = 2;
			} else {
				String temporary = adminAccountService.forcePasswordReset(email);
				System.out.println("Temporary password (shown once, change required at next login): " + temporary);
			}
		} else {
			System.err.println("unknown admin command: " + command);
			exitCode = 2;
		}
		final int code = exitCode;
		System.exit(SpringApplication.exit(context, () -> code));
	}

	private static String first(ApplicationArguments args, String name) {
		var values = args.getOptionValues(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}
}
