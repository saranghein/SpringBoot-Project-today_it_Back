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
  private final PlaceIngestionService service =
      new PlaceIngestionService(query, new TourApiPlaceNormalizationService(), store);

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
   * @param code 검증할 원천 분류 또는 응답 코드
   * @return 테스트 준비 또는 검증에 사용할 값
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
}
