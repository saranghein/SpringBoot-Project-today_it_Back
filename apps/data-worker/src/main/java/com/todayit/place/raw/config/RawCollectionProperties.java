package com.todayit.place.raw.config;

import com.todayit.place.raw.service.command.TourApiCollectionScope;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 명시적으로 활성화할 때만 실행하는 원본 수집·재수집·정제 미리보기 설정입니다.
 *
 * @param enabled 선택한 수집/정제 모드 실행 여부, 기본 false
 * @param limit 1~10, 기본 10. SAMPLE은 범위별 첫 페이지 크기, FULL은 페이지당 크기, 나머지 모드는 최대 원본 수
 * @param mode SAMPLE: 표본 수집, FULL: 선택 범위 전체 페이지, RETRY_FAILED: 실패 재수집, NORMALIZE: 정제 미리보기
 * @param sidoCode 수집할 법정동 시도 코드, 기본 11
 * @param sigunguCode 선택 시군구 코드, 기본 빈값으로 시도 전체
 * @param contentTypeId 공급원 관광타입 코드, 기본 12
 * @param targets 순서대로 수집할 지역·관광타입 조합. 비어 있으면 sidoCode·sigunguCode·contentTypeId 사용
 */
@ConfigurationProperties("todayit.place.collection")
public record RawCollectionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("10") int limit,
    @DefaultValue("SAMPLE") Mode mode,
    @DefaultValue("11") String sidoCode,
    @DefaultValue("") String sigunguCode,
    @DefaultValue("12") String contentTypeId,
    List<Target> targets) {

  /**
   * 실행 중 설정 목록이 변경되지 않도록 복사합니다. 코드 형식은 수집 범위로 변환할 때 검사합니다.
   *
   * @param enabled 실행 여부
   * @param limit 묶음 또는 페이지 크기
   * @param mode 실행 모드
   * @param sidoCode 기본 시도
   * @param sigunguCode 기본 시군구
   * @param contentTypeId 기본 관광타입
   * @param targets 순서대로 수집할 지역·관광타입 조합
   */
  public RawCollectionProperties {
    targets = targets == null ? List.of() : List.copyOf(targets);
  }

  /**
   * 모든 조합을 외부 호출 전에 검사합니다. 목록이 있으면 단일 설정과 합치지 않고 목록만 사용합니다.
   *
   * @return 설정 순서의 변경 불가능한 수집 범위
   * @throws IllegalArgumentException 코드가 잘못됐거나 동일 조합이 중복될 때
   */
  public List<TourApiCollectionScope> toScopes() {
    var scopes =
        targets.isEmpty() ? List.of(toScope()) : targets.stream().map(Target::toScope).toList();
    if (scopes.stream().distinct().count() != scopes.size()) {
      throw new IllegalArgumentException("동일한 수집 지역·관광타입 조합을 중복 지정할 수 없습니다.");
    }
    return scopes;
  }

  /**
   * 목록의 한 항목입니다. 시도와 관광타입은 명시하며 시군구를 생략하면 해당 시도 전체를 수집합니다.
   *
   * @param sidoCode 시도 코드
   * @param sigunguCode 선택 시군구 코드
   * @param contentTypeId 관광타입 코드
   */
  public record Target(
      String sidoCode, @DefaultValue("") String sigunguCode, String contentTypeId) {

    /**
     * 실행할 항목을 검증된 수집 범위로 바꿉니다.
     *
     * @return 지역·관광타입 코드 형식을 검증한 수집 범위
     * @throws IllegalArgumentException 코드 형식이 잘못됐을 때
     */
    public TourApiCollectionScope toScope() {
      return new TourApiCollectionScope(sidoCode, sigunguCode, contentTypeId);
    }
  }

  /**
   * sidoCode·sigunguCode·contentTypeId를 수집 범위로 변환합니다. 재수집·정제 모드는 이 범위를 사용하지 않습니다.
   *
   * @return 검증한 수집 범위
   * @throws IllegalArgumentException 코드 형식이 잘못됐을 때
   */
  public TourApiCollectionScope toScope() {
    return new TourApiCollectionScope(sidoCode, sigunguCode, contentTypeId);
  }

  /** 수동 실행에서 선택할 모드입니다. 한 번의 실행에서는 하나의 모드만 수행합니다. */
  public enum Mode {
    SAMPLE,
    FULL,
    RETRY_FAILED,
    NORMALIZE
  }
}
