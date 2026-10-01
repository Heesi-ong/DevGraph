package com.devgraph.common.observability;

import org.flywaydb.core.Flyway;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** readiness: 적용되지 않은 migration이 남아 있으면 트래픽을 받지 않는다(§21). */
@Component("migration")
public class MigrationHealthIndicator implements HealthIndicator {

	private final Flyway flyway;

	public MigrationHealthIndicator(Flyway flyway) {
		this.flyway = flyway;
	}

	@Override
	public Health health() {
		int pending = flyway.info().pending().length;
		return (pending == 0 ? Health.up() : Health.down()).withDetail("pending", pending).build();
	}
}
