package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponent;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceItem;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceDataSourceRegistry;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.raw.service.model.RawRetryTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

/** TourAPI 원본 묶음 수집과 실패 보존·재수집·중단 조건을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class TourApiRawCollectionServiceTest {

  @Mock private PlaceDataSource source;
  @Mock private ExternalPlaceRawCommandService store;
  @Mock private ExternalPlaceRawQueryService queryService;
  private TourApiRawCollectionService service;

  /** 공급원 Mock과 원본 저장·조회 경계를 초기화합니다. */
  @BeforeEach
  void setUp() {
    when(source.sourceKey()).thenReturn(new SourceKey("TOUR_API", "kor-service2"));
    service =
        new TourApiRawCollectionService(
            new PlaceDataSourceRegistry(List.of(source)), store, queryService);
  }

  /** 목록·공통·소개·이미지를 묶으며 원천 코드와 정상 빈 이미지를 보존하는지 검증합니다. */
  @Test
  void bundlesAllRawComponentsPreservingCodesAndAllowsEmptyImages() {
    givenPage(item("001"));
    givenDetails();

    var result = service.collectSample(10);

    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isZero();
    var payload = ArgumentCaptor.forClass(String.class);
    verify(store).saveSuccess(eq("TOUR_API"), eq("001"), payload.capture(), any(Instant.class));
    var json = JsonMapper.builder().build().readTree(payload.getValue());
    assertThat(json.path("list").path("lDongSignguCd").asText()).isEqualTo("110");
    assertThat(json.path("list").path("contentid").asText()).isEqualTo("001");
    assertThat(json.path("common").isArray()).isTrue();
    assertThat(json.path("intro").size()).isEqualTo(1);
    assertThat(json.path("images").isArray()).isTrue();
    assertThat(json.path("images").size()).isZero();
    verify(source).fetchComponent(new SourceComponentRequest("001", Component.BASIC, Map.of()));
    verify(source).fetchComponent(new SourceComponentRequest("001", Component.IMAGES, Map.of()));
    verify(source)
        .fetchComponent(
            new SourceComponentRequest(
                "001", Component.INTRODUCTION, Map.of("contentTypeId", "12")));
    verify(source)
        .fetchPage(
            new SourcePageRequest(
                Component.BASIC, "", 10, Map.of("lDongRegnCd", "11", "contentTypeId", "12")));
  }

  /** 건별 수집 실패 후 다음 건을 처리하고 재수집용 목록을 남기는지 검증합니다. */
  @Test
  void failedPlaceDoesNotPreventNextPlaceAndRetainsListForRetry() {
    givenPage(item("001"), item("002"));
    givenDetails();
    when(source.fetchComponent(
            new SourceComponentRequest(
                "001", Component.INTRODUCTION, Map.of("contentTypeId", "12"))))
        .thenThrow(new PlaceSourceException(Reason.TRANSIENT));

    var result = service.collectSample(10);

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    var payload = ArgumentCaptor.forClass(String.class);
    verify(store).saveFailure(eq("TOUR_API"), eq("001"), payload.capture(), eq(Reason.TRANSIENT));
    var list = JsonMapper.builder().build().readTree(payload.getValue());
    assertThat(list.path("list").path("contenttypeid").asText()).isEqualTo("12");
    assertThat(list.has("common")).isFalse();
    verify(store, never()).saveSuccess(eq("TOUR_API"), eq("001"), anyString(), any());
    verify(store).saveSuccess(eq("TOUR_API"), eq("002"), anyString(), any());
  }

  /**
   * 인증 또는 호출 한도 오류 뒤 추가 외부 호출을 중단하는지 검증합니다.
   *
   * @param reason 기대하는 안전한 오류 분류
   */
  @ParameterizedTest
  @EnumSource(
      value = Reason.class,
      names = {"AUTHENTICATION", "RATE_LIMIT"})
  void stopsFurtherCallsOnAuthenticationOrQuotaFailure(Reason reason) {
    givenPage(item("001"), item("002"));
    when(source.fetchComponent(any())).thenThrow(new PlaceSourceException(reason));

    var result = service.collectSample(10);

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    verify(source, never())
        .fetchComponent(new SourceComponentRequest("002", Component.BASIC, Map.of()));
  }

  /**
   * 필수 상세 응답이 비어 있으면 수집 실패로 처리하는지 검증합니다.
   *
   * @param component 조회할 구성 요소
   */
  @ParameterizedTest
  @EnumSource(
      value = Component.class,
      names = {"BASIC", "INTRODUCTION"})
  void emptyRequiredDetailIsFailure(Component component) {
    givenPage(item("001"));
    when(source.fetchComponent(any()))
        .thenAnswer(
            invocation -> {
              SourceComponentRequest request = invocation.getArgument(0);
              return detail(
                  request,
                  request.component() == component
                      ? "[]"
                      : "[{\"contentid\":\"001\",\"title\":\"장소\"}]");
            });

    assertThat(service.collectSample(10).failed()).isEqualTo(1);
    verify(store).saveFailure(eq("TOUR_API"), eq("001"), anyString(), eq(Reason.INVALID_RESPONSE));
  }

  /** 잘못된 JSON의 원문 메시지 없이 실패 분류만 저장하는지 검증합니다. */
  @Test
  void invalidJsonIsRecordedWithoutItsOriginalMessage() {
    givenPage(new SourceItem("001", "invalid-json"));

    assertThat(service.collectSample(10).failed()).isEqualTo(1);

    verify(store).saveFailure("TOUR_API", "001", null, Reason.INVALID_RESPONSE);
  }

  /** 요청과 상세 ID가 다르면 성공 원본을 저장하지 않는지 검증합니다. */
  @Test
  void mismatchedDetailIdDoesNotStoreSuccess() {
    givenPage(item("001"));
    when(source.fetchComponent(any()))
        .thenReturn(new SourceComponent("other", Component.BASIC, "[]", Instant.now()));

    assertThat(service.collectSample(10).failed()).isEqualTo(1);

    verify(store, never()).saveSuccess(anyString(), anyString(), anyString(), any());
  }

  /** 목록 조회 실패 시 식별할 수 없는 가짜 실패 행을 만들지 않는지 검증합니다. */
  @Test
  void listingFailureDoesNotCreateUnidentifiableFailureRow() {
    when(source.fetchPage(any())).thenThrow(new PlaceSourceException(Reason.TRANSIENT));

    assertThatThrownBy(() -> service.collectSample(10)).isInstanceOf(PlaceSourceException.class);

    verifyNoInteractions(store);
  }

  /** DB 저장 오류를 공급원 오류로 바꾸지 않고 실행을 중단하는지 검증합니다. */
  @Test
  void storageFailureAbortsInsteadOfBeingMisclassifiedAsProviderFailure() {
    givenPage(item("001"));
    givenDetails();
    doThrow(new DataAccessResourceFailureException("fixture"))
        .when(store)
        .saveSuccess(anyString(), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.collectSample(10))
        .isInstanceOf(DataAccessResourceFailureException.class);

    verify(store, never()).saveFailure(anyString(), anyString(), any(), any());
  }

  /**
   * 허용 범위를 벗어난 표본 건수를 거부하는지 검증합니다.
   *
   * @param limit 검증할 최대 처리 건수
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void rejectsUnboundedSample(int limit) {
    assertThatThrownBy(() -> service.collectSample(limit))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(store);
    verify(source, never()).fetchPage(any());
  }

  /** 목록 API 재호출 없이 저장된 목록과 관광타입으로 재수집하는지 검증합니다. */
  @Test
  void retriesStoredListWithoutFetchingPageAndUsesStoredContentType() {
    when(queryService.findFailed("TOUR_API", 10))
        .thenReturn(
            List.of(
                new RawRetryTarget(
                    "001",
                    "{\"list\":{\"contentid\":\"001\",\"contenttypeid\":\"39\",\"title\":\"음식점\"}}")));
    givenDetails();

    var result = service.retryFailed(10);

    assertThat(result.succeeded()).isEqualTo(1);
    verify(source, never()).fetchPage(any());
    verify(source)
        .fetchComponent(
            new SourceComponentRequest(
                "001", Component.INTRODUCTION, Map.of("contentTypeId", "39")));
    var payload = ArgumentCaptor.forClass(String.class);
    verify(store).saveSuccess(eq("TOUR_API"), eq("001"), payload.capture(), any());
    assertThat(
            JsonMapper.builder()
                .build()
                .readTree(payload.getValue())
                .path("list")
                .path("contenttypeid")
                .asText())
        .isEqualTo("39");
  }

  /** 재수집 대상이 없으면 공급원을 호출하지 않는지 검증합니다. */
  @Test
  void retryWithNoTargetsDoesNotCallProvider() {
    when(queryService.findFailed("TOUR_API", 10)).thenReturn(List.of());

    var result = service.retryFailed(10);

    assertThat(result.attempted()).isZero();
    verifyNoInteractions(store);
    verify(source, never()).fetchPage(any());
    verify(source, never()).fetchComponent(any());
  }

  /**
   * 사용할 수 없는 저장 목록을 추정 복구하지 않고 실패로 유지하는지 검증합니다.
   *
   * @param payload 정제 또는 복원할 원본 JSON
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "invalid-json",
        "{}",
        "{\"list\":[]}",
        "{\"list\":{\"contentid\":\"001\",\"title\":\"장소\"}}",
        "{\"list\":{\"contentid\":\"other\",\"contenttypeid\":\"12\",\"title\":\"장소\"}}"
      })
  void unusableStoredListRemainsFailedWithoutGuessingValues(String payload) {
    when(queryService.findFailed("TOUR_API", 10))
        .thenReturn(List.of(new RawRetryTarget("001", payload)));

    assertThat(service.retryFailed(10).failed()).isEqualTo(1);

    verify(store).saveFailure(eq("TOUR_API"), eq("001"), any(), eq(Reason.INVALID_RESPONSE));
    verify(source, never()).fetchComponent(any());
  }

  /** 재수집 대상을 한 번씩 처리하고 일시 오류 후 다음 건을 계속하는지 검증합니다. */
  @Test
  void retryProcessesEachSnapshotOnceAndContinuesAfterTransientFailure() {
    when(queryService.findFailed("TOUR_API", 10))
        .thenReturn(List.of(retryTarget("001"), retryTarget("002")));
    givenDetails();
    when(source.fetchComponent(new SourceComponentRequest("001", Component.BASIC, Map.of())))
        .thenThrow(new PlaceSourceException(Reason.TRANSIENT));

    var result = service.retryFailed(10);

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    verify(queryService).findFailed("TOUR_API", 10);
    verify(store).saveFailure(eq("TOUR_API"), eq("001"), anyString(), eq(Reason.TRANSIENT));
    verify(store).saveSuccess(eq("TOUR_API"), eq("002"), anyString(), any());
  }

  /**
   * 재수집 중 인증·호출 한도 오류가 발생하면 후속 호출을 중단하는지 검증합니다.
   *
   * @param reason 기대하는 안전한 오류 분류
   */
  @ParameterizedTest
  @EnumSource(
      value = Reason.class,
      names = {"AUTHENTICATION", "RATE_LIMIT"})
  void retryStopsOnAuthenticationOrQuotaFailure(Reason reason) {
    when(queryService.findFailed("TOUR_API", 10))
        .thenReturn(List.of(retryTarget("001"), retryTarget("002")));
    when(source.fetchComponent(any())).thenThrow(new PlaceSourceException(reason));

    var result = service.retryFailed(10);

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    verify(store).saveFailure(eq("TOUR_API"), eq("001"), anyString(), eq(reason));
    verify(source, never())
        .fetchComponent(new SourceComponentRequest("002", Component.BASIC, Map.of()));
  }

  /** 재수집 DB 저장 오류를 외부 수집 실패로 기록하지 않고 전파하는지 검증합니다. */
  @Test
  void retryStorageFailureAbortsInsteadOfBeingRecordedAsExternalFailure() {
    when(queryService.findFailed("TOUR_API", 10)).thenReturn(List.of(retryTarget("001")));
    givenDetails();
    doThrow(new DataAccessResourceFailureException("fixture"))
        .when(store)
        .saveSuccess(anyString(), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.retryFailed(10))
        .isInstanceOf(DataAccessResourceFailureException.class);

    verify(store, never()).saveFailure(anyString(), anyString(), any(), any());
  }

  /**
   * 저장된 목록을 포함하는 재수집 대상 스냅샷을 생성합니다.
   *
   * @param id 테스트 원천 장소 ID
   * @return 테스트 준비 또는 검증에 사용할 값
   */
  private RawRetryTarget retryTarget(String id) {
    return new RawRetryTarget(id, "{\"list\":" + item(id).payloadJson() + "}");
  }

  /**
   * 지정한 원천 ID를 가진 목록 항목을 생성합니다.
   *
   * @param id 테스트 원천 장소 ID
   * @return 테스트 준비 또는 검증에 사용할 값
   */
  private SourceItem item(String id) {
    return new SourceItem(
        id,
        "{\"contentid\":\""
            + id
            + "\",\"contenttypeid\":\"12\",\"title\":\"장소\",\"lDongSignguCd\":\"110\"}");
  }

  /**
   * 공급원이 반환할 첫 페이지 항목을 설정합니다.
   *
   * @param items 공급원이 반환할 목록 항목
   */
  private void givenPage(SourceItem... items) {
    when(source.fetchPage(any()))
        .thenReturn(new SourcePage(List.of(items), "", items.length, Instant.now()));
  }

  /** 공급원이 반환할 정상 공통·소개·이미지 상세를 설정합니다. */
  private void givenDetails() {
    when(source.fetchComponent(any()))
        .thenAnswer(
            invocation -> {
              SourceComponentRequest request = invocation.getArgument(0);
              return detail(
                  request,
                  request.component() == Component.IMAGES
                      ? "[]"
                      : "[{\"contentid\":\"" + request.sourceId() + "\",\"title\":\"장소\"}]");
            });
  }

  /**
   * 요청한 구성 요소와 원천 ID에 대응하는 상세 응답을 생성합니다.
   *
   * @param request 요청한 원천 ID와 구성 요소
   * @param payload 정제 또는 복원할 원본 JSON
   * @return 테스트 준비 또는 검증에 사용할 값
   */
  private SourceComponent detail(SourceComponentRequest request, String payload) {
    return new SourceComponent(request.sourceId(), request.component(), payload, Instant.now());
  }
}
