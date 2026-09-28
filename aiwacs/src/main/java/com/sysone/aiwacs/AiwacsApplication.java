package com.sysone.aiwacs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 지표 이력 1분 저장·오래된 이력 삭제 (MetricHistoryService)
public class AiwacsApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiwacsApplication.class, args);
	}

}
