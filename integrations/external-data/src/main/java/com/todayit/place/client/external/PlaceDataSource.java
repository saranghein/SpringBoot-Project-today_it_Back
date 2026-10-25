package com.todayit.place.client.external;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 외부 장소 원본을 조회하는 계약입니다. 정제·저장·재시도는 호출자가 담당합니다. */
public interface PlaceDataSource {

  /**
   * @return 공급원과 데이터셋 식별자
   */
  SourceKey sourceKey();

  /**
   * @return 페이지·개별 조회 지원 범위
   */
  SourceCapabilities capabilities();

  /**
   * 원본 페이지를 조회합니다. cursor는 같은 필터와 함께 재사용합니다.
   *
   * @param request 페이지 요청
   * @return 성공한 원본 페이지
   * @throws PlaceSourceException 외부 호출 또는 응답 검증 실패 시
   */
  SourcePage fetchPage(SourcePageRequest request);

  /**
   * 원천 ID에 해당하는 구성 요소를 조회합니다.
   *
   * @param request 구성 요소 요청
   * @return 원천 ID와 원본 응답
   * @throws PlaceSourceException 미지원 작업 또는 외부 호출 실패 시
   */
  SourceComponent fetchComponent(SourceComponentRequest request);

  /** 공급원 원본의 구성 요소입니다. */
  enum Component {
    BASIC,
    INTRODUCTION,
    IMAGES
  }

  /**
   * 원천 ID의 namespace입니다.
   *
   * @param provider 공급원 코드
   * @param dataset 상품·서비스 식별자
   */
  record SourceKey(String provider, String dataset) {
    /**
     * 식별자가 비어 있지 않은지 확인합니다.
     *
     * @param provider 공급원 코드
     * @param dataset 데이터셋 코드
     */
    public SourceKey {
      requireText(provider);
      requireText(dataset);
    }
  }

  /**
   * 공급원마다 다른 수집 능력을 표현합니다. 증분 조회는 현재 지원하지 않습니다.
   *
   * @param pagedComponents 페이지 수집 가능한 구성 요소
   * @param individualComponents ID 조회 가능한 구성 요소
   * @param configurablePageSize 요청으로 페이지 크기를 지정할 수 있는지 여부
   */
  record SourceCapabilities(
      Set<Component> pagedComponents,
      Set<Component> individualComponents,
      boolean configurablePageSize) {
    /**
     * 지원 목록을 방어적으로 복사합니다.
     *
     * @param pagedComponents 페이지 수집 구성 요소
     * @param individualComponents 개별 수집 구성 요소
     * @param configurablePageSize 페이지 크기 지정 가능 여부
     */
    public SourceCapabilities {
      pagedComponents = Set.copyOf(pagedComponents);
      individualComponents = Set.copyOf(individualComponents);
    }
  }

  /**
   * 비밀값을 포함하지 않는 재실행 가능한 페이지 요청입니다.
   *
   * @param component 구성 요소
   * @param cursor 최초 요청은 빈 문자열, 이후 응답 cursor
   * @param pageSize 요청 크기, 크기 미지원 공급원은 전달하지 않고 응답 크기로 페이지 계산
   * @param filters 공급원에서 허용한 조회 조건만 전달
   */
  record SourcePageRequest(
      Component component, String cursor, int pageSize, Map<String, String> filters) {
    /**
     * 요청을 검증하고 조회 조건을 복사합니다.
     *
     * @param component 구성 요소
     * @param cursor 페이지 cursor
     * @param pageSize 페이지 크기
     * @param filters 조회 조건
     * @throws IllegalArgumentException 크기가 1~1000 범위를 벗어날 때
     */
    public SourcePageRequest {
      Objects.requireNonNull(component);
      Objects.requireNonNull(cursor);
      filters = Map.copyOf(filters);
      if (pageSize < 1 || pageSize > 1000) {
        throw new IllegalArgumentException("페이지 크기는 1~1000이어야 합니다.");
      }
    }
  }

  /**
   * 성공 페이지의 원천별 항목입니다. 사진 목록에는 같은 sourceId가 여러 번 나올 수 있습니다.
   *
   * @param sourceId 문자열 원천 ID
   * @param payloadJson 가공하지 않은 필드의 JSON 표현
   */
  record SourceItem(String sourceId, String payloadJson) {}

  /**
   * 성공 페이지입니다. 비어 있는 nextCursor는 마지막 페이지를 뜻합니다.
   *
   * @param items 원천 항목
   * @param nextCursor 다음 페이지 cursor
   * @param totalCount 공급원이 보고한 전체 항목 수
   * @param collectedAt 성공 수집 시각
   */
  record SourcePage(
      List<SourceItem> items, String nextCursor, long totalCount, Instant collectedAt) {
    /**
     * 항목 목록을 방어적으로 복사합니다.
     *
     * @param items 원천 항목
     * @param nextCursor 다음 cursor
     * @param totalCount 전체 항목 수
     * @param collectedAt 수집 시각
     */
    public SourcePage {
      items = List.copyOf(items);
    }
  }

  /**
   * 원천별 상세 수집 요청입니다.
   *
   * @param sourceId 원천 ID
   * @param component 구성 요소
   * @param filters 공급원별 보조 조건
   */
  record SourceComponentRequest(String sourceId, Component component, Map<String, String> filters) {
    /**
     * 식별자와 구성 요소를 검증합니다.
     *
     * @param sourceId 원천 ID
     * @param component 구성 요소
     * @param filters 보조 조건
     */
    public SourceComponentRequest {
      requireText(sourceId);
      Objects.requireNonNull(component);
      filters = Map.copyOf(filters);
    }
  }

  /**
   * 개별 구성 요소의 원본 JSON입니다. 빈 배열도 성공 응답과 오류를 구분해 보존합니다.
   *
   * @param sourceId 원천 ID
   * @param component 구성 요소
   * @param payloadJson 원본 필드의 JSON 표현
   * @param collectedAt 성공 수집 시각
   */
  record SourceComponent(
      String sourceId, Component component, String payloadJson, Instant collectedAt) {}

  /**
   * 원천 식별자의 빈값을 거부하며 숫자 변환이나 공백 제거로 원문을 바꾸지 않습니다.
   *
   * @param value 검증할 식별자
   * @throws IllegalArgumentException 식별자가 null이거나 공백만 있을 때
   */
  private static void requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("식별자는 비어 있을 수 없습니다.");
    }
  }
}
