package com.todayit.place.raw.config;

import com.todayit.place.raw.service.command.TourApiCollectionScope;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DB에 등록된 시도 전체로 수집 대상을 확장하는 선택 설정입니다.
 *
 * @param enabled 전국 대상 자동 생성 여부, 기본 false
 * @param contentTypeIds 명시적으로 수집할 관광타입 목록, 자동 선택하지 않음
 */
@ConfigurationProperties("todayit.place.collection.nationwide")
public record NationwideCollectionProperties(
    @DefaultValue("false") boolean enabled, List<String> contentTypeIds) {

  /**
   * 관광타입 목록을 복사하고 활성화한 경우 누락·중복·코드 형식을 검사합니다.
   *
   * @param enabled 자동 생성 여부
   * @param contentTypeIds 관광타입 코드 목록
   * @throws IllegalArgumentException 활성화했으나 관광타입 목록이 비거나 중복·형식 오류가 있을 때
   */
  public NationwideCollectionProperties {
    contentTypeIds = contentTypeIds == null ? List.of() : List.copyOf(contentTypeIds);
    if (enabled) {
      if (contentTypeIds.isEmpty()
          || contentTypeIds.stream().distinct().count() != contentTypeIds.size()) {
        throw new IllegalArgumentException("전국 수집의 관광타입을 중복 없이 명시해야 합니다.");
      }
      for (String type : contentTypeIds) {
        new TourApiCollectionScope("11", "", type);
      }
    }
  }
}
