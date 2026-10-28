package com.todayit.place.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult.Status;
import com.todayit.place.raw.service.ExternalPlaceRawQueryService;
import com.todayit.place.raw.service.TourApiPlaceNormalizationService;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;

/** 원본 정제와 건별 적재 조정 및 성공·보류·실패 집계를 검증합니다. */
class PlaceIngestionServiceTest {

  private final ExternalPlaceRawQueryService query = mock(ExternalPlaceRawQueryService.class);
  private final PlaceUpsertCommandService store = mock(PlaceUpsertCommandService.class);
  private final PlaceIngestionFailureService failures = mock(PlaceIngestionFailureService.class);
  private final PlaceIngestionService service =
      new PlaceIngestionService(query, new TourApiPlaceNormalizationService(), store, failures);

  /** 커밋 성공·제외·검수·지역 보류를 각각 집계하고 건너뛴 값을 저장하지 않는지 검증합니다. */
  @Test
  void countsCommittedExcludedReviewAndMissingRegionWithoutStoringSkippedValues() {
    when(query.findSucceeded("TOUR_API", 10))
        .thenReturn(
            List.of(
                snapshot("001", "VE030100"),
                snapshot("002", "EV020200"),
                snapshot("003", "EX050800"),
                snapshot("004", "VE030100")));
    when(store.upsert(argThat(value -> value != null && value.sourceId().equals("001"))))
        .thenReturn(new PlaceUpsertResult("001", Status.APPLIED, 42));
    when(store.upsert(argThat(value -> value != null && value.sourceId().equals("004"))))
        .thenReturn(new PlaceUpsertResult("004", Status.REGION_NOT_FOUND, null));

    var result = service.ingestTourApi(10);

    assertThat(result).isEqualTo(new PlaceIngestionResult(4, 1, 1, 1, 1, 0));
    verify(store, org.mockito.Mockito.times(2)).upsert(any());
    verify(query).findSucceeded("TOUR_API", 10);
  }

  /** 저장 실패를 집계한 뒤 다음 장소를 계속 저장하는지 검증합니다. */
  @Test
  void storageFailureIsCountedAndNextPlaceStillCommits() {
    when(query.findSucceeded("TOUR_API", 10))
        .thenReturn(List.of(snapshot("001", "VE030100"), snapshot("002", "VE030100")));
    when(store.upsert(any()))
        .thenThrow(new DataIntegrityViolationException("fixture"))
        .thenReturn(new PlaceUpsertResult("002", Status.APPLIED, 43));

    assertThat(service.ingestTourApi(10)).isEqualTo(new PlaceIngestionResult(2, 1, 0, 0, 0, 1));
  }

  /** 트랜잭션 커밋 실패를 저장 성공으로 집계하지 않는지 검증합니다. */
  @Test
  void transactionCommitFailureIsNotCountedAsStored() {
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of(snapshot("001", "VE030100")));
    when(store.upsert(any())).thenThrow(new UnexpectedRollbackException("fixture"));

    assertThat(service.ingestTourApi(10)).isEqualTo(new PlaceIngestionResult(1, 0, 0, 0, 0, 1));
  }

  /** 실패 로그에 원본 번호를 남기면서 본문과 예외 메시지는 노출하지 않는지 검증합니다. */
  @Test
  void failureLogIdentifiesRawRowWithoutLeakingPayloadOrExceptionMessage() {
    Logger logger = (Logger) LoggerFactory.getLogger(PlaceIngestionService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      when(query.findSucceeded("TOUR_API", 10))
          .thenReturn(List.of(snapshot("private-source-id", "VE030100")));
      when(store.upsert(any()))
          .thenThrow(new DataIntegrityViolationException("private-db-value serviceKey=secret"));

      service.ingestTourApi(10);

      var failures =
          appender.list.stream()
              .filter(event -> event.getFormattedMessage().contains("PLACE_INGESTION_ITEM_FAILED"))
              .toList();
      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().getFormattedMessage())
          .contains("장소 적재 실패", "원본번호=1", "오류유형=DataIntegrityViolationException")
          .doesNotContain("private-source-id", "private-db-value", "serviceKey", "secret", "서울");
      assertThat(failures.getFirst().getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 성공 원본이 없으면 정상 빈 결과를 반환하고 저장하지 않는지 검증합니다. */
  @Test
  void emptySuccessfulSnapshotSetIsNormalAndDoesNotWrite() {
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of());

    assertThat(service.ingestTourApi(10)).isEqualTo(new PlaceIngestionResult(0, 0, 0, 0, 0, 0));
    verifyNoInteractions(store);
  }

  /**
   * 범위를 벗어난 실행 건수를 DB 조회·저장 전에 거부하는지 검증합니다.
   *
   * @param limit 검증할 최대 처리 건수
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void invalidLimitDoesNotReadOrWriteDatabase(int limit) {
    assertThatThrownBy(() -> service.ingestTourApi(limit))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(query, store);
  }

  /** 최초 원본 조회 실패를 빈 성공으로 바꾸지 않고 전파하는지 검증합니다. */
  @Test
  void initialDatabaseReadFailurePropagatesWithoutClaimingAnEmptySuccess() {
    when(query.findSucceeded("TOUR_API", 10))
        .thenThrow(new DataIntegrityViolationException("fixture"));

    assertThatThrownBy(() -> service.ingestTourApi(10))
        .isInstanceOf(DataIntegrityViolationException.class);
    verifyNoInteractions(store);
  }

  /** 예상하지 못한 프로그램 오류를 건별 저장 실패로 숨기지 않는지 검증합니다. */
  @Test
  void unexpectedProgrammingFailureIsNotMisclassifiedAsRowStorageFailure() {
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of(snapshot("001", "VE030100")));
    when(store.upsert(any())).thenThrow(new IllegalStateException("fixture"));

    assertThatThrownBy(() -> service.ingestTourApi(10)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * 지정한 원천 ID와 분류를 가진 성공 원본 스냅샷을 생성합니다.
   *
   * @param sourceId 공급원 장소 ID
   * @param code 원천 소분류 코드
   * @return 지정한 원천 ID와 소분류 코드를 가진 성공 원본 스냅샷
   */
  private RawPlaceSnapshot snapshot(String sourceId, String code) {
    String payload =
        """
        {"list":{"contentid":"%s","lclsSystm3":"%s","title":"공원","addr1":"서울 종로구","mapy":"37.5","mapx":"127.1","lDongRegnCd":"11","lDongSignguCd":"110"},
        "common":[{"contentid":"%s"}],"intro":[{"contentid":"%s"}],"images":[]}
        """
            .formatted(sourceId, code, sourceId, sourceId);
    return new RawPlaceSnapshot(1, "TOUR_API", sourceId, "SUCCESS", payload);
  }

  /** 보류만 있는 첫 묶음도 통과하고 건별 실패 후에도 마지막 묶음까지 처리하는지 확인합니다. */
  @Test
  void fullIngestionAdvancesPastDeferredAndFailedRowsAndAggregatesEveryBatch() {
    when(query.findSucceededUpperBound("TOUR_API")).thenReturn(70L);
    when(query.findSucceededAfter("TOUR_API", 0, 70, 2))
        .thenReturn(List.of(withId(10, "EX050800"), withId(20, "EV020200")));
    when(query.findSucceededAfter("TOUR_API", 20, 70, 2))
        .thenReturn(List.of(withId(30, "VE030100"), withId(40, "VE030100")));
    when(query.findSucceededAfter("TOUR_API", 40, 70, 2))
        .thenReturn(List.of(withId(50, "VE030100")));
    when(query.findSucceededAfter("TOUR_API", 50, 70, 2)).thenReturn(List.of());
    when(store.upsert(any()))
        .thenReturn(new PlaceUpsertResult("30", Status.REGION_NOT_FOUND, null))
        .thenThrow(new DataIntegrityViolationException("fixture"))
        .thenReturn(new PlaceUpsertResult("50", Status.APPLIED, 1));

    assertThat(service.ingestAllTourApi(2)).isEqualTo(new PlaceIngestionResult(5, 1, 1, 1, 1, 1));

    verify(query).findSucceededUpperBound("TOUR_API");
    verify(query).findSucceededAfter("TOUR_API", 50, 70, 2);
    verify(store, org.mockito.Mockito.times(3)).upsert(any());
  }

  /** 성공 원본이 없으면 조회 상한만 확인하고 정상 종료하는지 검증합니다. */
  @Test
  void fullIngestionWithNoSuccessfulRowsDoesNotFetchOrStoreBatches() {
    when(query.findSucceededUpperBound("TOUR_API")).thenReturn(0L);

    assertThat(service.ingestAllTourApi(10)).isEqualTo(new PlaceIngestionResult(0, 0, 0, 0, 0, 0));

    verifyNoInteractions(store);
    verify(query).findSucceededUpperBound("TOUR_API");
    org.mockito.Mockito.verifyNoMoreInteractions(query);
  }

  /** 실패 기록 자체가 불가능하면 실패 대상을 잃은 채 다음 건을 진행하지 않는지 확인합니다. */
  @Test
  void failureRecordingErrorAbortsRatherThanSilentlyLosingRetryTarget() {
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of(snapshot("001", "VE030100")));
    when(store.upsert(any())).thenThrow(new DataIntegrityViolationException("fixture"));
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException("fixture"))
        .when(failures)
        .recordFailure(1L, PlaceIngestionFailureService.Code.DATA_INTEGRITY);

    assertThatThrownBy(() -> service.ingestTourApi(10))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
  }

  /** 실패 목록의 PK를 사용해 재실패·검수 뒤에도 계속 진행하고 정상 원본 전체를 다시 읽지 않는지 확인합니다. */
  @Test
  void retryTraversesFailureListOnceDespitePersistentFailureAndDeferredRows() {
    when(failures.findUpperBound("TOUR_API")).thenReturn(5L);
    when(failures.findAfter("TOUR_API", 0, 5, 2))
        .thenReturn(
            List.of(
                new com.todayit.place.ingestion.service.model.PlaceIngestionRetryTarget(
                    1, withId(10, "VE030100")),
                new com.todayit.place.ingestion.service.model.PlaceIngestionRetryTarget(
                    2, withId(20, "EX050800"))));
    when(failures.findAfter("TOUR_API", 2, 5, 2))
        .thenReturn(
            List.of(
                new com.todayit.place.ingestion.service.model.PlaceIngestionRetryTarget(
                    5, withId(30, "VE030100"))));
    when(store.upsert(any()))
        .thenThrow(new DataIntegrityViolationException("fixture"))
        .thenReturn(new PlaceUpsertResult("30", Status.APPLIED, 1));

    assertThat(service.retryFailedTourApi(2)).isEqualTo(new PlaceIngestionResult(3, 1, 0, 1, 0, 1));

    verify(failures).recordFailure(10L, PlaceIngestionFailureService.Code.DATA_INTEGRITY);
    verifyNoInteractions(query);
  }

  /** 전체 실행도 범위를 벗어난 묶음 크기를 DB 접근 전에 거부하는지 확인합니다. */
  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void fullIngestionValidatesBatchSizeBeforeDatabaseAccess(int size) {
    assertThatThrownBy(() -> service.ingestAllTourApi(size))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(query, store);
  }

  /** 이후 묶음의 DB 읽기 오류를 최종 성공으로 바꾸지 않는지 확인합니다. */
  @Test
  void fullIngestionPropagatesLaterReadFailureAfterEarlierCommits() {
    when(query.findSucceededUpperBound("TOUR_API")).thenReturn(2L);
    when(query.findSucceededAfter("TOUR_API", 0, 2, 1)).thenReturn(List.of(withId(1, "VE030100")));
    when(query.findSucceededAfter("TOUR_API", 1, 2, 1))
        .thenThrow(new DataIntegrityViolationException("fixture"));
    when(store.upsert(any())).thenReturn(new PlaceUpsertResult("1", Status.APPLIED, 1));

    assertThatThrownBy(() -> service.ingestAllTourApi(1))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(store).upsert(any());
  }

  /**
   * ID가 전진하지 않거나 실행 상한을 벗어나면 잘못된 묶음을 저장하지 않는지 확인합니다.
   *
   * @param invalidId 잘못 조회된 마지막 원본 ID
   */
  @ParameterizedTest
  @ValueSource(longs = {0L, 3L})
  void fullIngestionRejectsNonAdvancingOrOutOfRangeRawIds(long invalidId) {
    when(query.findSucceededUpperBound("TOUR_API")).thenReturn(2L);
    when(query.findSucceededAfter("TOUR_API", 0, 2, 1))
        .thenReturn(List.of(withId(invalidId, "VE030100")));

    assertThatThrownBy(() -> service.ingestAllTourApi(1)).isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(store);
  }

  /**
   * 순차 조회에 사용할 고유 PK를 가진 원본을 만듭니다.
   *
   * @param id 원본 PK와 테스트 원천 ID
   * @param code 소분류 코드
   * @return 지정한 PK의 원본 스냅샷
   */
  private RawPlaceSnapshot withId(long id, String code) {
    var original = snapshot(Long.toString(id), code);
    return new RawPlaceSnapshot(
        id, original.provider(), original.sourceId(), original.status(), original.payloadJson());
  }
}
