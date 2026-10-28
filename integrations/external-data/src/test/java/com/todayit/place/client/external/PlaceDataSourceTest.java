package com.todayit.place.client.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.client.external.culture.CulturePlaceDataSource;
import com.todayit.place.client.external.redtable.RedtablePlaceDataSource;
import com.todayit.place.client.external.support.PlaceSourceCallLogger;
import com.todayit.place.client.external.support.PlaceSourceHttpClient;
import com.todayit.place.client.external.tourapi.TourApiPlaceDataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 HTTP 서버로 공급원 요청·응답 계약과 오류·로그의 안전성을 검증합니다. */
class PlaceDataSourceTest {

  private static final String TEST_KEY = "fixture+/= value";
  private final AtomicReference<String> responseBody = new AtomicReference<>();
  private final AtomicReference<URI> requestedUri = new AtomicReference<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger calls = new AtomicInteger();
  private HttpServer server;
  private TourApiPlaceDataSource tour;
  private RedtablePlaceDataSource redtable;
  private CulturePlaceDataSource culture;

  /**
   * 실제 공급원 대신 사용할 로컬 HTTP 서버와 테스트 Client를 준비합니다.
   *
   * @throws Exception 테스트 자원 준비 또는 비동기 실행에 실패할 때
   */
  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          requestedUri.set(exchange.getRequestURI());
          byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    var http =
        new PlaceSourceHttpClient(
            WebClient.create(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            TEST_KEY,
            Duration.ofSeconds(3));
    tour = new TourApiPlaceDataSource(http, "TodayItTest");
    redtable = new RedtablePlaceDataSource(http);
    culture = new CulturePlaceDataSource(http);
    responseBody.set(tourPage("[{\"contentid\":\"00012\",\"title\":\"장소\"}]", 2, 1, 1));
  }

  /** 테스트에서 실행한 로컬 HTTP 서버를 종료합니다. */
  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  /** TourAPI 원천 필드를 보존하고 Decoding 키를 한 번만 인코딩하는지 검증합니다. */
  @Test
  void tourPreservesRawFieldsAndEncodesDecodedKeyExactlyOnce() {
    var result = tour.fetchPage(page(Component.BASIC, "", 1, Map.of("lDongRegnCd", "11")));

    assertThat(result.items().getFirst().sourceId()).isEqualTo("00012");
    assertThat(result.items().getFirst().payloadJson()).contains("장소");
    assertThat(result.nextCursor()).isEqualTo("v1:2");
    assertThat(requestedUri.get().getPath()).isEqualTo("/areaBasedList2");
    assertThat(requestedUri.get().getRawQuery())
        .contains("serviceKey=fixture%2B%2F%3D%20value", "lDongRegnCd=11");
    assertThat(calls.get()).isEqualTo(1);
  }

  /** 이어받은 cursor로 다음 페이지를 조회하고 마지막 페이지에서 종료하는지 검증합니다. */
  @Test
  void resumedPageUsesRequestedCursorAndTerminates() {
    responseBody.set(tourPage("[{\"contentid\":\"8\"}]", 2, 1, 2));
    var result = tour.fetchPage(page(Component.BASIC, "v1:2", 1, Map.of()));
    assertThat(result.nextCursor()).isEmpty();
    assertThat(requestedUri.get().getQuery()).contains("pageNo=2");
  }

  /**
   * 마지막 7건을 페이지 크기로 반환해도 요청한 10건 단위로 종료 위치를 계산하는지 검증합니다.
   *
   * @param reportedRows 공급원이 반환한 numOfRows
   */
  @ParameterizedTest
  @CsvSource({"7", "10"})
  void tourLastPartialPageUsesRequestedPageSize(int reportedRows) {
    responseBody.set(
        tourPage(
            "[{\"contentid\":\"761\"},{\"contentid\":\"762\"},{\"contentid\":\"763\"},{\"contentid\":\"764\"},{\"contentid\":\"765\"},{\"contentid\":\"766\"},{\"contentid\":\"767\"}]",
            767,
            reportedRows,
            77));

    var result = tour.fetchPage(page(Component.BASIC, "v1:77", 10, Map.of()));

    assertThat(result.items()).hasSize(7);
    assertThat(result.nextCursor()).isEmpty();
    assertThat(requestedUri.get().getQuery()).contains("pageNo=77", "numOfRows=10");
    assertThat(calls.get()).isEqualTo(1);
  }

  /** 정상 빈 목록에서 numOfRows도 0인 응답을 허용하는지 검증합니다. */
  @Test
  void tourEmptyListAllowsZeroReportedRows() {
    responseBody.set(tourPage("[]", 0, 0, 1));
    var result = tour.fetchPage(page(Component.BASIC, "", 10, Map.of()));
    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isEmpty();
  }

  /** 요청 크기나 실제 반환 건수 어느 쪽과도 일치하지 않는 numOfRows를 거부하는지 검증합니다. */
  @Test
  void tourRejectsInconsistentReportedRows() {
    responseBody.set(tourPage("[{\"contentid\":\"11\"}]", 11, 2, 2));
    assertReason(
        Reason.INVALID_RESPONSE, () -> tour.fetchPage(page(Component.BASIC, "v1:2", 10, Map.of())));
  }

  /** 중간 페이지가 짧은 경우 실제 반환 건수를 numOfRows에 넣어도 누락을 거부하는지 검증합니다. */
  @Test
  void tourRejectsTruncatedIntermediatePage() {
    responseBody.set(tourPage("[{\"contentid\":\"1\"}]", 20, 1, 1));
    assertReason(
        Reason.INVALID_RESPONSE, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
  }

  /** 정상 빈 관광 목록을 오류로 처리하지 않는지 검증합니다. */
  @Test
  void successfulEmptyTourListIsNotAnError() {
    responseBody.set(tourPage("[]", 0, 10, 1));
    var result = tour.fetchPage(page(Component.BASIC, "", 10, Map.of()));
    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isEmpty();
  }

  /**
   * 공통·소개·이미지 구분에 맞는 TourAPI 상세 경로를 호출하는지 검증합니다.
   *
   * @param component 조회할 구성 요소
   * @param path 기대하는 공급원 호출 경로
   */
  @ParameterizedTest
  @CsvSource({"BASIC,/detailCommon2", "INTRODUCTION,/detailIntro2", "IMAGES,/detailImage2"})
  void tourFetchesEachComponent(Component component, String path) {
    responseBody.set(tourPage("[{\"contentid\":\"7\",\"overview\":\"소개\"}]", 1, 1000, 1));
    var result =
        tour.fetchComponent(
            new SourceComponentRequest(
                "7",
                component,
                component == Component.INTRODUCTION ? Map.of("contentTypeId", "12") : Map.of()));
    assertThat(requestedUri.get().getPath()).isEqualTo(path);
    var parameterNames =
        List.of(requestedUri.get().getRawQuery().split("&")).stream()
            .map(parameter -> parameter.substring(0, parameter.indexOf('=')))
            .toList();
    var expectedNames =
        new ArrayList<>(
            List.of(
                "MobileOS",
                "MobileApp",
                "_type",
                "contentId",
                "numOfRows",
                "pageNo",
                "serviceKey"));
    if (component == Component.INTRODUCTION) {
      expectedNames.add("contentTypeId");
      assertThat(requestedUri.get().getQuery()).contains("contentTypeId=12");
    }
    if (component == Component.IMAGES) {
      expectedNames.add("imageYN");
      assertThat(requestedUri.get().getQuery()).contains("imageYN=Y");
    }
    assertThat(parameterNames).containsExactlyInAnyOrderElementsOf(expectedNames);
    assertThat(result.payloadJson()).contains("소개");
    assertThat(JsonMapper.builder().build().readTree(result.payloadJson()).isArray()).isTrue();
  }

  /**
   * 소개 외 상세 요청에 관광타입을 넣으면 외부 호출 전에 거부하는지 검증합니다.
   *
   * @param component 조회할 구성 요소
   */
  @ParameterizedTest
  @CsvSource({"BASIC", "IMAGES"})
  void tourRejectsContentTypeOutsideIntroductionBeforeCallingProvider(Component component) {
    assertReason(
        Reason.INVALID_REQUEST,
        () ->
            tour.fetchComponent(
                new SourceComponentRequest("7", component, Map.of("contentTypeId", "12"))));
    assertThat(calls.get()).isZero();
  }

  /** 소개 조회의 관광타입 누락을 외부 호출 전에 거부하는지 검증합니다. */
  @Test
  void tourRequiresContentTypeForIntroductionBeforeCallingProvider() {
    assertReason(
        Reason.INVALID_REQUEST,
        () ->
            tour.fetchComponent(new SourceComponentRequest("7", Component.INTRODUCTION, Map.of())));
    assertThat(calls.get()).isZero();
  }

  /** TourAPI 상세의 식별자가 요청 ID와 다르면 거부하는지 검증합니다. */
  @Test
  void tourRejectsMismatchedDetailId() {
    responseBody.set(tourPage("[{\"contentid\":\"8\"}]", 1, 1000, 1));
    assertReason(
        Reason.INVALID_RESPONSE,
        () -> tour.fetchComponent(new SourceComponentRequest("7", Component.BASIC, Map.of())));
  }

  /** 여러 페이지로 나뉜 이미지 응답을 일부만 수집하고 성공 처리하지 않는지 검증합니다. */
  @Test
  void tourDoesNotSilentlyDropPaginatedImages() {
    responseBody.set(tourPage("[{\"contentid\":\"7\"}]", 1001, 1000, 1));
    assertReason(
        Reason.INVALID_RESPONSE,
        () -> tour.fetchComponent(new SourceComponentRequest("7", Component.IMAGES, Map.of())));
  }

  /**
   * HTTP 성공 응답에도 공급원 업무 결과 코드를 검사하는지 검증합니다.
   *
   * @param code 검증할 원천 분류 또는 응답 코드
   * @param reason 기대하는 안전한 오류 분류
   */
  @ParameterizedTest
  @CsvSource({
    "20,AUTHENTICATION",
    "30,AUTHENTICATION",
    "22,RATE_LIMIT",
    "23,RATE_LIMIT",
    "01,TRANSIENT",
    "10,INVALID_REQUEST",
    "unknown,INVALID_RESPONSE"
  })
  void httpSuccessStillValidatesBusinessCode(String code, Reason reason) {
    responseBody.set("{\"response\":{\"header\":{\"resultCode\":\"" + code + "\"}}}");
    assertReason(reason, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
    assertThat(calls.get()).isEqualTo(1);
  }

  /**
   * HTTP 오류를 안전한 분류로 변환하고 Adapter 내부에서 재시도하지 않는지 검증합니다.
   *
   * @param responseStatus 공급원이 반환할 HTTP 상태 코드
   * @param reason 기대하는 안전한 오류 분류
   */
  @ParameterizedTest
  @CsvSource({
    "401,AUTHENTICATION",
    "403,AUTHENTICATION",
    "429,RATE_LIMIT",
    "503,TRANSIENT",
    "400,INVALID_REQUEST",
    "302,INVALID_REQUEST"
  })
  void httpStatusIsSanitizedAndNeverRetriedInsideAdapter(int responseStatus, Reason reason) {
    status.set(responseStatus);
    responseBody.set(TEST_KEY);
    var failure =
        assertReason(reason, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
    assertThat(failure.getMessage()).doesNotContain(TEST_KEY, "http", "serviceKey");
    assertThat(failure.getCause()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  /** 게이트웨이 XML 오류를 정상 빈 JSON으로 취급하지 않는지 검증합니다. */
  @Test
  void gatewayXmlErrorIsNotTreatedAsAnEmptyJsonResponse() {
    responseBody.set(
        "<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>030</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>");
    assertReason(
        Reason.AUTHENTICATION, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
  }

  /**
   * 레드테이블의 정의된 일괄 경로와 서버 페이지 크기를 사용하는지 검증합니다.
   *
   * @param component 조회할 구성 요소
   * @param path 기대하는 공급원 호출 경로
   */
  @ParameterizedTest
  @CsvSource({"BASIC,/api/rstr", "INTRODUCTION,/api/rstr/oprt", "IMAGES,/api/rstr/img"})
  void redtableUsesDocumentedBulkEndpointsAndServerPageSize(Component component, String path) {
    responseBody.set(
        "{\"header\":{\"resultCode\":\"00\",\"totalCount\":4,\"numOfRows\":2,\"pageNo\":1},\"body\":[{\"RSTR_ID\":7},{\"RSTR_ID\":7}]}");
    var result = redtable.fetchPage(page(component, "", 10, Map.of()));
    assertThat(result.items()).hasSize(2);
    assertThat(result.nextCursor()).isEqualTo("v1:2");
    assertThat(requestedUri.get().getPath()).isEqualTo(path);
    assertThat(requestedUri.get().getQuery()).doesNotContain("numOfRows");
    assertThat(redtable.capabilities().configurablePageSize()).isFalse();
  }

  /** 지원하지 않는 레드테이블 ID 조회를 네트워크 호출 없이 거부하는지 검증합니다. */
  @Test
  void redtableRejectsUndocumentedIdLookupWithoutNetwork() {
    assertReason(
        Reason.UNSUPPORTED,
        () -> redtable.fetchComponent(new SourceComponentRequest("7", Component.BASIC, Map.of())));
    assertThat(calls.get()).isZero();
  }

  /** 문화시설 XML을 해석하고 원천 시설 ID를 보존하는지 검증합니다. */
  @Test
  void cultureParsesDocumentedFacilityXmlAndKeepsFacilityId() {
    responseBody.set(
        "<response><header><reseultCode>00</reseultCode></header><body><totalCount>1</totalCount><PageNo>1</PageNo><numOfrows>10</numOfrows><items><item><seq>0007</seq><culName>문화시설</culName></item></items></body></response>");
    var result = culture.fetchPage(page(Component.BASIC, "", 10, Map.of("facility", "artgallery")));
    assertThat(result.items().getFirst().sourceId()).isEqualTo("0007");
    assertThat(result.items().getFirst().payloadJson()).contains("문화시설");
    assertThat(requestedUri.get().getPath()).isEqualTo("/artgallery");
    assertThat(requestedUri.get().getQuery())
        .contains("PageNo=1", "numOfrows=10")
        .doesNotContain("facility=");
  }

  /** 문화시설 상세를 조회하고 미지원 이미지 조회를 거부하는지 검증합니다. */
  @Test
  void cultureFetchesFacilityDetailAndRejectsImages() {
    responseBody.set(
        "<response><header><resultCode>00</resultCode></header><body><items><item><seq>7</seq><culName>시설</culName></item></items></body></response>");
    assertThat(
            culture
                .fetchComponent(new SourceComponentRequest("7", Component.BASIC, Map.of()))
                .payloadJson())
        .contains("시설");
    assertThat(requestedUri.get().getPath()).isEqualTo("/detail");
    assertReason(
        Reason.UNSUPPORTED,
        () -> culture.fetchComponent(new SourceComponentRequest("7", Component.IMAGES, Map.of())));
    assertThat(calls.get()).isEqualTo(1);
  }

  /** XML 외부 엔티티를 통한 외부 자원 접근을 차단하는지 검증합니다. */
  @Test
  void externalXmlEntitiesAreRejected() {
    responseBody.set(
        "<!DOCTYPE response [<!ENTITY xxe SYSTEM 'file:///should-not-read'>]><response>&xxe;</response>");
    assertReason(
        Reason.INVALID_RESPONSE,
        () -> culture.fetchPage(page(Component.BASIC, "", 10, Map.of("facility", "museum"))));
  }

  /** 인증 파라미터 덮어쓰기와 경로 주입을 외부 호출 전에 거부하는지 검증합니다. */
  @Test
  void credentialAndPathInjectionAreRejectedBeforeCallingProvider() {
    assertReason(
        Reason.INVALID_REQUEST,
        () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of("serviceKey", "override"))));
    assertReason(
        Reason.INVALID_REQUEST,
        () -> culture.fetchPage(page(Component.BASIC, "", 10, Map.of("facility", "../detail"))));
    assertReason(
        Reason.INVALID_REQUEST, () -> tour.fetchPage(page(Component.BASIC, "../1", 10, Map.of())));
    assertThat(calls.get()).isZero();
  }

  /**
   * 건수·페이지 정보가 불일치하면 다음 cursor를 반환하지 않는지 검증합니다.
   *
   * @param rows 공급원이 반환할 항목 JSON
   * @param total 공급원 전체 항목 수
   * @param size 응답 페이지 크기
   * @param pageNumber 응답 페이지 번호
   */
  @ParameterizedTest
  @CsvSource({"[],3,10,1", "[{\"contentid\":\"7\"}],3,2,1", "[{}],1,10,1", "[],0,10,2"})
  void invalidPageCannotAdvanceCursor(String rows, long total, int size, int pageNumber) {
    responseBody.set(tourPage(rows, total, size, pageNumber));
    assertReason(
        Reason.INVALID_RESPONSE, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
  }

  /** 잘못된 JSON과 전체 건수가 누락된 응답을 거부하는지 검증합니다. */
  @Test
  void malformedJsonAndMissingCountAreRejected() {
    responseBody.set("not json");
    assertReason(
        Reason.INVALID_RESPONSE, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
    responseBody.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{}}}");
    assertReason(
        Reason.INVALID_RESPONSE, () -> tour.fetchPage(page(Component.BASIC, "", 10, Map.of())));
  }

  /** 요청 시간 초과 시 인증 정보가 포함될 수 있는 전송 예외를 노출하지 않는지 검증합니다. */
  @Test
  void timeoutDoesNotExposeTransportException() {
    var webClient = WebClient.builder().exchangeFunction(request -> Mono.never()).build();
    var http =
        new PlaceSourceHttpClient(
            webClient, "https://example.invalid", TEST_KEY, Duration.ofMillis(20));
    var failure = assertReason(Reason.TRANSIENT, () -> http.get("/list", Map.of()));
    assertThat(failure.getCause()).isNull();
  }

  /** 발급키가 없으면 네트워크 요청 없이 인증 실패로 처리하는지 검증합니다. */
  @Test
  void missingKeyFailsWithoutNetwork() {
    var http =
        new PlaceSourceHttpClient(
            WebClient.create(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "",
            Duration.ofSeconds(1));
    assertReason(Reason.AUTHENTICATION, () -> http.get("/list", Map.of()));
    assertThat(calls.get()).isZero();
  }

  /** 응답 크기 제한 초과를 일시 오류가 아닌 잘못된 응답으로 분류하는지 검증합니다. */
  @Test
  void oversizedHttpBodyIsInvalidResponseRatherThanTransientFailure() {
    responseBody.set(
        tourPage("[{\"contentid\":\"7\",\"title\":\"fixture-private-payload\"}]", 1, 1, 1));
    var client =
        WebClient.builder().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32)).build();
    var http =
        new PlaceSourceHttpClient(
            client,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            TEST_KEY,
            Duration.ofSeconds(3));
    var source = new TourApiPlaceDataSource(http, "TodayItTest");

    var failure =
        assertReason(
            Reason.INVALID_RESPONSE,
            () -> source.fetchPage(page(Component.BASIC, "", 1, Map.of())));

    assertThat(failure.getMessage())
        .doesNotContain(TEST_KEY, "serviceKey", "fixture-private-payload");
    assertThat(failure.getCause()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  /** 공급원 로그가 안전한 요약만 포함하고 원문·키·예외 객체를 노출하지 않는지 검증합니다. */
  @Test
  void sourceLogsContainOnlySafeSummaryAndNoThrowable() {
    Logger logger = (Logger) LoggerFactory.getLogger(PlaceSourceCallLogger.class);
    Level previousLevel = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      responseBody.set(
          tourPage("[{\"contentid\":\"7\",\"title\":\"fixture-private-payload\"}]", 1, 1, 1));
      tour.fetchPage(page(Component.BASIC, "", 1, Map.of()));
      status.set(503);
      assertReason(Reason.TRANSIENT, () -> tour.fetchPage(page(Component.BASIC, "", 1, Map.of())));

      assertThat(appender.list).hasSize(2);
      assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains(
              "place_source_success",
              "외부 조회 성공",
              "공급원=TOUR_API",
              "작업=PAGE",
              "조회건수=1",
              "다음페이지=false",
              "소요시간=");
      assertThat(appender.list.get(1).getLevel()).isEqualTo(Level.WARN);
      assertThat(appender.list.get(1).getFormattedMessage())
          .contains("place_source_failure", "외부 조회 실패", "사유=TRANSIENT");
      for (var event : appender.list) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(TEST_KEY, "serviceKey", "127.0.0.1", "fixture-private-payload");
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }

  /** 공급원·데이터셋 쌍으로 구현을 선택하고 중복 등록을 거부하는지 검증합니다. */
  @Test
  void registrySelectsByNamespaceAndRejectsDuplicates() {
    var sources = new ArrayList<PlaceDataSource>(List.of(tour, redtable, culture));
    var registry = new PlaceDataSourceRegistry(sources);
    sources.clear();
    assertThat(registry.get(tour.sourceKey())).isSameAs(tour);
    assertThatThrownBy(() -> new PlaceDataSourceRegistry(List.of(tour, tour)))
        .isInstanceOf(IllegalArgumentException.class);
    assertReason(
        Reason.UNSUPPORTED, () -> new PlaceDataSourceRegistry(List.of()).get(tour.sourceKey()));
  }

  /** 비활성 공급원은 인증키나 네트워크 호출 없이 초기화되는지 검증합니다. */
  @Test
  void disabledSourcesDoNotRequireCredentialsOrMakeNetworkCalls() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceSourceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(PlaceDataSourceRegistry.class);
              assertThat(context.getBeansOfType(PlaceDataSource.class)).isEmpty();
            });
    assertThat(calls.get()).isZero();
  }

  /** 명시적으로 활성화한 공급원만 등록되는지 검증합니다. */
  @Test
  void onlyEnabledSourceIsRegistered() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceSourceConfiguration.class)
        .withPropertyValues(
            "todayit.place.sources.tourapi.enabled=true",
            "todayit.place.sources.tourapi.service-key=fixture")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(TourApiPlaceDataSource.class);
              assertThat(context.getBeansOfType(PlaceDataSource.class)).hasSize(1);
            });
  }

  /**
   * 각 공급원에 같은 형태의 페이지 요청을 전달하기 위한 테스트 입력을 생성합니다.
   *
   * @param component 조회 구성 요소
   * @param cursor 최초 빈값 또는 재실행 cursor
   * @param size 요청 페이지 크기
   * @param filters 공급원별 조회 조건
   * @return 검증된 불변 페이지 요청
   */
  private SourcePageRequest page(
      Component component, String cursor, int size, Map<String, String> filters) {
    return new SourcePageRequest(component, cursor, size, filters);
  }

  /**
   * HTTP 성공과 항목·전체 건수·페이지 정합성을 독립적으로 검증할 TourAPI 응답을 구성합니다.
   *
   * @param items item에 넣을 JSON 표현
   * @param total 공급원이 보고한 전체 건수
   * @param size 응답 페이지 크기
   * @param pageNumber 응답 페이지 번호
   * @return 성공 업무 코드를 가진 합성 JSON 응답
   */
  private String tourPage(String items, long total, int size, int pageNumber) {
    return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":"
        + items
        + "},\"totalCount\":"
        + total
        + ",\"numOfRows\":"
        + size
        + ",\"pageNo\":"
        + pageNumber
        + "}}}";
  }

  /**
   * 외부 기술 예외가 계약의 오류 분류로 변환됐는지 확인합니다.
   *
   * @param reason 기대하는 실패 분류
   * @param action 실패를 유발할 공급원 호출
   * @return 메시지·cause 비식별화 등을 추가 검증할 수집 예외
   * @throws AssertionError 호출이 실패하지 않거나 오류 분류가 다를 때
   */
  private PlaceSourceException assertReason(Reason reason, Runnable action) {
    var failure = assertThrows(PlaceSourceException.class, action::run);
    assertThat(failure.getReason()).isEqualTo(reason);
    return failure;
  }
}
