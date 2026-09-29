package com.devgraph.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 최근 조회 기록(§9.2 KNOW-08)처럼 응답을 늦추면 안 되는 부수 작업용. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
