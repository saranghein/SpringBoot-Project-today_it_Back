package com.todayit.place.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 명시적으로 켰을 때만 활성화하는 정기 실행 설정입니다.
 *
 * @param enabled 상시 스케줄 실행 여부, 기본 false
 * @param collectCron 전체 수집·적재 주기, 기본 매일 03시
 * @param retryCron 저장 실패 재처리 주기, 기본 6시간마다
 * @param zone 실행 시간대, 기본 Asia/Seoul
 */
@ConfigurationProperties("todayit.place.schedule")
public record PlaceScheduleProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("0 0 3 * * *") String collectCron,
    @DefaultValue("0 0 */6 * * *") String retryCron,
    @DefaultValue("Asia/Seoul") String zone) {}
