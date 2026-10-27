package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Reason;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import com.todayit.place.service.model.NormalizedPlace.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 원천 분류·필수값·좌표·지역·소개·이미지 정제 규칙을 검증합니다. */
class TourApiPlaceNormalizationServiceTest {

  private final TourApiPlaceNormalizationService service = new TourApiPlaceNormalizationService();
  private final JsonMapper json = JsonMapper.builder().build();

  /** 원본을 변경하지 않고 정제하며 지역 코드가 보존되는지 검증합니다. */
  @Test
  void normalizesWithoutChangingOriginalAndPreservesRegionCodes() {
    var payload = payload();
    String original = payload.toString();

    var result = normalize(payload);

    assertThat(result.status()).isEqualTo(Status.NORMALIZED);
    assertThat(result.reason()).isNull();
    assertThat(result.place().name()).isEqualTo("공원");
    assertThat(result.place().address()).isEqualTo("서울 종로구 청운동");
    assertThat(result.place().latitude().toPlainString()).isEqualTo("37.00000001");
    assertThat(result.place().longitude().toPlainString()).isEqualTo("127.00000001");
    assertThat(result.place().sidoCode()).isEqualTo("11");
    assertThat(result.place().sigunguCode()).isEqualTo("110");
    assertThat(payload.toString()).isEqualTo(original);
  }

  /**
   * 등록된 소분류 코드와 정확히 일치할 때 서비스 분류로 변환하는지 검증합니다.
   *
   * @param code 검증할 원천 분류 또는 응답 코드
   * @param category 기대하는 서비스 분류
   */
  @ParameterizedTest
  @CsvSource({
    "FD040100,RESTAURANT",
    "FD030100,CAFE_DESSERT",
    "VE070600,EXHIBITION_MUSEUM",
    "VE060100,PERFORMANCE",
    "EX020100,EXPERIENCE",
    "VE020100,PLAY_LEISURE",
    "VE030100,NATURE_WALK",
    "SH050100,SHOPPING",
    "VE010200,LANDMARK_VIEW"
  })
  void mapsOnlyExactApprovedCodes(String code, Category category) {
    var payload = payload();
    list(payload).put("lclsSystm3", code);

    assertThat(normalize(payload).place().category()).isEqualTo(category);
  }

  /**
   * 행사·영화·숙박·여행 코스 분류를 제외하는지 검증합니다.
   *
   * @param code 검증할 원천 분류 또는 응답 코드
   */
  @ParameterizedTest
  @ValueSource(strings = {"EV020200", "EV030100", "VE060200", "AC010100", "VE050200", "C0112001"})
  void excludesEventsMoviesAccommodationAndCourses(String code) {
    var payload = payload();
    list(payload).put("lclsSystm3", code);

    var result = normalize(payload);

    assertThat(result.status()).isEqualTo(Status.EXCLUDED);
    assertThat(result.reason()).isEqualTo(Reason.CATEGORY_NOT_ALLOWED);
    assertThat(result.place()).isNull();
  }

  /**
   * 미등록·누락 분류를 관광타입이나 장소명으로 추정하지 않는지 검증합니다.
   *
   * @param code 검증할 원천 분류 또는 응답 코드
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "FD040300",
        "EX050100",
        "LS010500",
        "VE070400",
        "VE020500",
        "VE030999",
        "ZZ999999",
        ""
      })
  void unapprovedOrMissingCodesNeverFallBackToTourismTypeOrTitle(String code) {
    var payload = payload();
    list(payload).put("lclsSystm3", code).put("contenttypeid", "39").put("title", "카페");

    var result = normalize(payload);

    assertThat(result.status()).isEqualTo(Status.REVIEW_REQUIRED);
    assertThat(result.reason()).isEqualTo(Reason.CATEGORY_UNMAPPED);
    assertThat(result.place()).isNull();
  }

  /** 공통 값이 없으면 목록의 장소명과 주소 전체를 선택하는지 검증합니다. */
  @Test
  void usesListNameAndWholeAddressWhenCommonValuesAreMissing() {
    var payload = payload();
    common(payload).put("title", " ").put("addr1", " ").put("addr2", "섞으면 안 되는 상세주소");

    var place = normalize(payload).place();

    assertThat(place.name()).isEqualTo("목록명");
    assertThat(place.address()).isEqualTo("목록주소 목록상세");
  }

  /** 응답 간 값을 섞지 않고 하나의 응답에서 좌표 쌍을 선택하는지 검증합니다. */
  @Test
  void usesWholeCoordinatePairInsteadOfMixingResponses() {
    var payload = payload();
    common(payload).put("mapy", "").put("mapx", "128");

    var place = normalize(payload).place();

    assertThat(place.latitude().toPlainString()).isEqualTo("37.12345679");
    assertThat(place.longitude().toPlainString()).isEqualTo("127.12345679");
  }

  /** 응답 간 값을 섞지 않고 하나의 응답에서 지역 코드 쌍을 선택하는지 검증합니다. */
  @Test
  void usesWholeRegionPairInsteadOfMixingResponses() {
    var payload = payload();
    common(payload).put("lDongRegnCd", "").put("lDongSignguCd", "999");

    var place = normalize(payload).place();

    assertThat(place.sidoCode()).isEqualTo("11");
    assertThat(place.sigunguCode()).isEqualTo("110");
  }

  /**
   * 잘못된 좌표를 추정·보정하지 않고 검수 대상으로 분류하는지 검증합니다.
   *
   * @param field 검증할 원천 필드명
   * @param value 검증할 필드 값
   */
  @ParameterizedTest
  @CsvSource({
    "mapy,91",
    "mapy,-91",
    "mapx,181",
    "mapx,-181",
    "mapy,0",
    "mapx,0",
    "mapy,NaN",
    "mapy,Infinity",
    "mapx,wrong",
    "mapy,0.000000001"
  })
  void invalidCoordinatesAreReviewRequiredWithoutGuessing(String field, String value) {
    var payload = payload();
    common(payload).put(field, value);
    list(payload).put(field, value);

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_COORDINATES);
  }

  /**
   * 형식이 잘못되거나 누락된 시도·시군구 코드를 거부하는지 검증합니다.
   *
   * @param sido 시도 코드
   * @param sigungu 시군구 코드
   */
  @ParameterizedTest
  @CsvSource({"1,110", "11,1", "11,000", "00,110", "AA,110"})
  void rejectsMalformedOrMissingRegionCodes(String sido, String sigungu) {
    var payload = payload();
    common(payload).put("lDongRegnCd", sido).put("lDongSignguCd", sigungu);

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_REGION_CODES);
  }

  /** Unicode 문자 수 제한을 적용하고 긴 장소명을 임의로 자르지 않는지 검증합니다. */
  @Test
  void respectsUnicodeCharacterLimitWithoutTruncation() {
    var payload = payload();
    common(payload).put("title", "😀".repeat(50));
    assertThat(normalize(payload).status()).isEqualTo(Status.NORMALIZED);

    common(payload).put("title", "😀".repeat(51));

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_NAME);
    assertThat(normalize(payload).place()).isNull();
  }

  /** DB 길이 제한을 넘는 주소를 자르지 않고 거부하는지 검증합니다. */
  @Test
  void rejectsAddressBeyondDatabaseLimitWithoutTruncation() {
    var payload = payload();
    common(payload).put("addr1", "가".repeat(255)).put("addr2", "");
    assertThat(normalize(payload).status()).isEqualTo(Status.NORMALIZED);

    common(payload).put("addr2", "상세");

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_ADDRESS);
  }

  /**
   * 잘못된 JSON이 예외로 유출되거나 원문을 노출하지 않는지 검증합니다.
   *
   * @param payload 정제 또는 복원할 원본 JSON
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "null", "[]", "{}", "not-json"})
  void malformedPayloadDoesNotEscapeOrExposeOriginal(String payload) {
    var result = service.normalize(new RawPlaceSnapshot(1, "TOUR_API", "001", "SUCCESS", payload));

    assertThat(result.reason()).isEqualTo(Reason.INVALID_PAYLOAD);
    assertThat(result.place()).isNull();
  }

  /** 원본과 상세 응답의 장소 ID가 다르면 거부하는지 검증합니다. */
  @Test
  void rejectsMismatchedDetailedId() {
    var payload = payload();
    common(payload).put("contentid", "other");

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_PAYLOAD);
  }

  /** 공통 상세 필드의 잘못된 타입을 목록 값으로 숨기지 않는지 검증합니다. */
  @Test
  void malformedCommonScalarDoesNotSilentlyFallBackToValidListValue() {
    var payload = payload();
    common(payload).set("addr1", json.createArrayNode());

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_PAYLOAD);
  }

  /** 소개를 추출하고 권리 정보를 보존하며 이미지 중복을 제거하는지 검증합니다. */
  @Test
  void extractsDescriptionAndDeduplicatesImagesWithoutLosingRightsMetadata() {
    var payload = payload();
    common(payload).put("overview", "<p>공원&nbsp;소개</p><br>산책");
    var images = json.createArrayNode();
    var image =
        json.createObjectNode()
            .put("contentid", "001")
            .put("originimgurl", " https://tong.visitkorea.or.kr/img.jpg?size=original ")
            .put("smallimageurl", "https://tong.visitkorea.or.kr/small.jpg")
            .put("cpyrhtDivCd", "fixture-rights")
            .put("serialnum", "0001")
            .put("imgname", " 공원 사진 ");
    images.add(image);
    images.add(image.deepCopy());
    payload.set("images", images);
    String original = payload.toString();

    var place = normalize(payload).place();

    assertThat(place.description()).contains("공원 소개", "산책").doesNotContain("<p>");
    assertThat(place.images()).hasSize(1);
    assertThat(place.images().getFirst().url())
        .isEqualTo("https://tong.visitkorea.or.kr/img.jpg?size=original");
    assertThat(place.images().getFirst().copyrightCode()).isEqualTo("fixture-rights");
    assertThat(place.images().getFirst().serialNumber()).isEqualTo("0001");
    assertThat(place.images().getFirst().name()).isEqualTo("공원 사진");
    assertThat(payload.toString()).isEqualTo(original);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> place.images().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 잘못된 이미지 URL을 다운로드하지 않고 장소를 검수 대상으로 분류하는지 검증합니다.
   *
   * @param url url 테스트 입력
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "javascript:alert(1)",
        "data:image/png;base64,AA",
        "file:///tmp/photo",
        "/image.jpg",
        "https://user:password@example.com/image.jpg",
        "https://bad host/image.jpg"
      })
  void invalidImageUrlDefersPlaceWithoutDownloading(String url) {
    var payload = payload();
    payload.set(
        "images",
        json.createArrayNode()
            .add(json.createObjectNode().put("contentid", "001").put("originimgurl", url)));

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_IMAGES);
  }

  /** 같은 이미지 URL의 권리 정보가 충돌하면 검수 대상으로 분류하는지 검증합니다. */
  @Test
  void duplicateImageWithConflictingRightsIsNotSilentlyAccepted() {
    var payload = payload();
    payload.set(
        "images",
        json.createArrayNode()
            .add(
                json.createObjectNode()
                    .put("contentid", "001")
                    .put("originimgurl", "https://example.com/image.jpg")
                    .put("cpyrhtDivCd", "first"))
            .add(
                json.createObjectNode()
                    .put("contentid", "001")
                    .put("originimgurl", "https://example.com/image.jpg")
                    .put("cpyrhtDivCd", "different")));

    assertThat(normalize(payload).reason()).isEqualTo(Reason.INVALID_IMAGES);
  }

  /** 소개 누락과 빈 이미지 목록을 선택 값으로 허용하는지 검증합니다. */
  @Test
  void optionalMissingDescriptionAndEmptyImagesRemainOptional() {
    var place = normalize(payload()).place();

    assertThat(place.description()).isNull();
    assertThat(place.images()).isEmpty();
  }

  /**
   * 수집 성공 상태가 아닌 원본을 정제하지 않는지 검증합니다.
   *
   * @param status 검증할 수집 상태
   */
  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "PENDING"})
  void doesNotNormalizeIncompleteCollection(String status) {
    var result =
        service.normalize(new RawPlaceSnapshot(1, "TOUR_API", "001", status, payload().toString()));

    assertThat(result.reason()).isEqualTo(Reason.NOT_COLLECTED);
  }

  /**
   * 테스트 JSON을 성공 원본 스냅샷으로 감싸 정제 결과를 반환합니다.
   *
   * @param payload 정제 또는 복원할 원본 JSON
   * @return 전달한 원본 JSON의 정제 결과
   */
  private PlaceNormalizationResult normalize(ObjectNode payload) {
    return service.normalize(
        new RawPlaceSnapshot(1, "TOUR_API", "001", "SUCCESS", payload.toString()));
  }

  /**
   * 테스트 원본의 목록 객체를 반환합니다.
   *
   * @param payload 정제 또는 복원할 원본 JSON
   * @return 원본 JSON의 list 객체
   */
  private ObjectNode list(ObjectNode payload) {
    return (ObjectNode) payload.path("list");
  }

  /**
   * 테스트 원본의 공통 상세 객체를 반환합니다.
   *
   * @param payload 정제 또는 복원할 원본 JSON
   * @return 원본 JSON의 첫 번째 common 상세 객체
   */
  private ObjectNode common(ObjectNode payload) {
    return (ObjectNode) payload.path("common").get(0);
  }

  /**
   * 필수 상세와 지역·분류를 포함한 정상 원본 JSON을 생성합니다.
   *
   * @return 필수 상세·지역 코드·소분류 코드를 포함하는 정상 원본 JSON
   */
  private ObjectNode payload() {
    return (ObjectNode)
        json.readTree(
            """
        {"list":{"contentid":"001","contenttypeid":"12","lclsSystm3":"VE030100","title":"목록명","addr1":"목록주소","addr2":"목록상세","mapy":"37.123456789","mapx":"127.123456789","lDongRegnCd":"11","lDongSignguCd":"110"},
         "common":[{"contentid":"001","title":" 공원 ","addr1":" 서울 종로구 ","addr2":" 청운동 ","mapy":"37.000000005","mapx":"127.000000005","lDongRegnCd":"11","lDongSignguCd":"110"}],
         "intro":[{"contentid":"001"}],"images":[]}
        """);
  }
}
