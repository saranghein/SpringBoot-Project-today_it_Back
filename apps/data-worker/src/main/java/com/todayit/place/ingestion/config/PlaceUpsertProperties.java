package com.todayit.place.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지역 기준 데이터와 Migration을 준비한 환경에서 명시적으로 활성화하는 서비스 반영 설정입니다.
 *
 * @param enabled 지역 조회·저장 기능 등록 여부, 기본 false. 등록만으로 적재하지 않음
 * @param runEnabled 시작 시 소량 적재 실행 여부, 기본 false
 * @param limit 실행당 최대 원본 수, 1~10, 기본 10
 */
@ConfigurationProperties("todayit.place.upsert")
public record PlaceUpsertProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("false") boolean runEnabled,
    @DefaultValue("10") int limit) {}
