package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.RawCollectionResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 여러 범위 순차 수집의 순서·집계·오류별 중단 경계를 검증합니다. */
class TourApiCollectionPlanServiceTest {

  private final TourApiRawCollectionService collection = mock(TourApiRawCollectionService.class);
  private final TourApiCollectionPlanService service = new TourApiCollectionPlanService(collection);
  private final TourApiCollectionScope first = new TourApiCollectionScope("11", "", "12");
  private final TourApiCollectionScope second = new TourApiCollectionScope("26", "110", "39");

  /** 상세 실패가 있어도 다음 범위로 이동하고 설정 순서대로 결과를 합산하는지 확인합니다. */
  @Test
  void continuesAfterDetailFailureAndAggregatesInOrder() {
    when(collection.collectAll(2, first)).thenReturn(new RawCollectionResult(3, 2, 1));
    when(collection.collectAll(2, second)).thenReturn(new RawCollectionResult(5, 5, 0));
    assertThat(service.collect(List.of(first, second), 2, true))
        .isEqualTo(new RawCollectionResult(8, 7, 1));
    var order = inOrder(collection);
    order.verify(collection).collectAll(2, first);
    order.verify(collection).collectAll(2, second);
    verifyNoMoreInteractions(collection);
  }

  /** 인증·쿼터 중단이 뒤의 범위를 호출하지 않는지 확인합니다. */
  @Test
  void stoppedScopePreventsFollowingTargets() {
    when(collection.collectAll(2, first)).thenReturn(new RawCollectionResult(1, 0, 1, true));
    assertThat(service.collect(List.of(first, second), 2, true))
        .isEqualTo(new RawCollectionResult(1, 0, 1, true));
    verify(collection).collectAll(2, first);
    verifyNoMoreInteractions(collection);
  }

  /** 목록·DB 오류로 다음 범위 전에 중단하는지 확인합니다. */
  @Test
  void listFailureAbortsPlanBeforeNextScope() {
    when(collection.collectAll(2, first))
        .thenThrow(new PlaceSourceException(PlaceSourceException.Reason.TRANSIENT));
    assertThatThrownBy(() -> service.collect(List.of(first, second), 2, true))
        .isInstanceOf(PlaceSourceException.class);
    verify(collection).collectAll(2, first);
    verifyNoMoreInteractions(collection);
  }

  /** FULL의 빈 범위는 다음 지역 수집을 막지 않는지 확인합니다. */
  @Test
  void emptyFullScopeDoesNotSkipFollowingScope() {
    when(collection.collectAll(2, first)).thenReturn(new RawCollectionResult(0, 0, 0));
    when(collection.collectAll(2, second)).thenReturn(new RawCollectionResult(1, 1, 0));
    assertThat(service.collect(List.of(first, second), 2, true))
        .isEqualTo(new RawCollectionResult(1, 1, 0));
  }

  /** SAMPLE은 범위마다 표본을 수집하는지 확인합니다. */
  @Test
  void sampleCollectsFirstPageFromEveryScope() {
    when(collection.collectSample(2, first)).thenReturn(new RawCollectionResult(2, 2, 0));
    when(collection.collectSample(2, second)).thenReturn(new RawCollectionResult(2, 2, 0));
    assertThat(service.collect(List.of(first, second), 2, false))
        .isEqualTo(new RawCollectionResult(4, 4, 0));
    var order = inOrder(collection);
    order.verify(collection).collectSample(2, first);
    order.verify(collection).collectSample(2, second);
    verifyNoMoreInteractions(collection);
  }

  /** SAMPLE의 빈 범위가 뒤의 성공에 가려지지 않는지 확인합니다. */
  @Test
  void emptySampleScopeFailsBeforeNextScope() {
    when(collection.collectSample(2, first)).thenReturn(new RawCollectionResult(0, 0, 0));
    assertThatThrownBy(() -> service.collect(List.of(first, second), 2, false))
        .isInstanceOf(IllegalStateException.class);
    verify(collection).collectSample(2, first);
    verifyNoMoreInteractions(collection);
  }

  /** 빈 목록과 같은 범위 중복을 외부 호출 전에 거부하는지 확인합니다. */
  @Test
  void emptyOrDuplicateTargetsDoNotCallSource() {
    assertThatThrownBy(() -> service.collect(List.of(), 2, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.collect(List.of(first, first), 2, true))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(collection);
  }

  /**
   * 페이지 크기를 외부 호출 전에 검사하는지 확인합니다.
   *
   * @param limit 잘못된 페이지 크기
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void invalidSizeDoesNotCallSource(int limit) {
    assertThatThrownBy(() -> service.collect(List.of(first), limit, true))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(collection);
  }
}
