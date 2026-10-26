package com.todayit.place.raw.service;

import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Reason;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import com.todayit.place.raw.support.PlaceDescriptionText;
import com.todayit.place.raw.support.TourApiCategoryMapping;
import com.todayit.place.service.model.NormalizedPlace;
import com.todayit.place.service.model.NormalizedPlace.Image;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 TourAPI 원본만 정제합니다. 외부 호출·DB 저장·지역 ID 조회를 수행하지 않습니다. */
@Service
public class TourApiPlaceNormalizationService {

  private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
  private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 원본을 변경하지 않고 필수값·허용 분류·지역 코드 형식을 검사합니다.
   *
   * @param raw 저장된 원본 스냅샷
   * @return 정상 변환 또는 원문을 포함하지 않는 보류·제외 사유
   */
  public PlaceNormalizationResult normalize(RawPlaceSnapshot raw) {
    if (!"TOUR_API".equals(raw.provider())) {
      return result(raw, Status.EXCLUDED, Reason.UNSUPPORTED_PROVIDER, null);
    }
    if (!"SUCCESS".equals(raw.status())) {
      return result(raw, Status.EXCLUDED, Reason.NOT_COLLECTED, null);
    }
    try {
      JsonNode payload = read(raw.payloadJson());
      JsonNode list = payload.path("list");
      JsonNode common = payload.path("common").path(0);
      validatePayload(raw.sourceId(), payload);
      String categoryCode = text(list, "lclsSystm3");
      if (categoryCode.isEmpty()) {
        categoryCode = text(common, "lclsSystm3");
      }
      if (TourApiCategoryMapping.isExcluded(categoryCode)) {
        return result(raw, Status.EXCLUDED, Reason.CATEGORY_NOT_ALLOWED, null);
      }
      var category = TourApiCategoryMapping.findCategory(categoryCode);
      if (category.isEmpty()) {
        return result(raw, Status.REVIEW_REQUIRED, Reason.CATEGORY_UNMAPPED, null);
      }
      String name = text(common, "title");
      if (name.isEmpty()) {
        name = text(list, "title");
      }
      requireLength(name, 50, Reason.INVALID_NAME);
      JsonNode addressSource = text(common, "addr1").isEmpty() ? list : common;
      String address = text(addressSource, "addr1");
      String addressDetail = text(addressSource, "addr2");
      if (!addressDetail.isEmpty()) {
        address += " " + addressDetail;
      }
      if (text(addressSource, "addr1").isEmpty()) {
        throw new InvalidValue(Reason.INVALID_ADDRESS);
      }
      requireLength(address, 255, Reason.INVALID_ADDRESS);
      JsonNode coordinates = hasPair(common, "mapy", "mapx") ? common : list;
      BigDecimal latitude = coordinate(text(coordinates, "mapy"), MAX_LATITUDE);
      BigDecimal longitude = coordinate(text(coordinates, "mapx"), MAX_LONGITUDE);
      JsonNode region = hasPair(common, "lDongRegnCd", "lDongSignguCd") ? common : list;
      String sido = text(region, "lDongRegnCd");
      String sigungu = text(region, "lDongSignguCd");
      if (!sido.matches("[0-9]{2}")
          || !sigungu.matches("[0-9]{3}")
          || sido.equals("00")
          || sigungu.equals("000")) {
        throw new InvalidValue(Reason.INVALID_REGION_CODES);
      }
      NormalizedPlace place =
          new NormalizedPlace(
              name,
              address,
              latitude,
              longitude,
              category.orElseThrow(),
              sido,
              sigungu,
              categoryCode,
              description(common),
              images(payload.path("images")));
      return result(raw, Status.NORMALIZED, null, place);
    } catch (InvalidValue exception) {
      return result(raw, Status.REVIEW_REQUIRED, exception.reason, null);
    }
  }

  /**
   * 공통 소개만 일반 텍스트로 변환합니다. 소개가 없다고 장소명을 복사하거나 문장을 생성하지 않습니다.
   *
   * @param common 공통 상세 객체
   * @return 일반 텍스트 소개 또는 null
   */
  private String description(JsonNode common) {
    return PlaceDescriptionText.fromHtml(text(common, "overview"));
  }

  /**
   * 원본 URL의 정확한 일치로 중복을 제거하고 최초 순서와 권리 메타데이터를 보존합니다.
   *
   * @param rows 이미지 상세 배열
   * @return 불변 이미지 후보 목록
   * @throws InvalidValue URL이 잘못되거나 같은 URL의 권리 구분이 충돌할 때
   */
  private List<Image> images(JsonNode rows) {
    var images = new LinkedHashMap<String, Image>();
    for (JsonNode row : rows) {
      String url = imageUrl(text(row, "originimgurl"), true);
      Image image =
          new Image(
              url,
              imageUrl(text(row, "smallimageurl"), false),
              optional(text(row, "imgname")),
              optional(text(row, "serialnum")),
              optional(text(row, "cpyrhtDivCd")));
      Image previous = images.putIfAbsent(url, image);
      if (previous != null && !Objects.equals(previous.copyrightCode(), image.copyrightCode())) {
        throw new InvalidValue(Reason.INVALID_IMAGES);
      }
    }
    return List.copyOf(images.values());
  }

  /**
   * URL은 다운로드하지 않고 구조만 검사합니다. 쿼리 변경·HTTP 승격 등으로 원천 주소를 변형하지 않습니다.
   *
   * @param value 원천 URL
   * @param required 필수 URL인지 여부
   * @return 검증한 URL, 선택 값이 없으면 null
   * @throws InvalidValue URL이 없거나 2048자 초과, HTTP(S)가 아니거나 사용자 정보가 포함될 때
   */
  private String imageUrl(String value, boolean required) {
    if (value.isEmpty() && !required) {
      return null;
    }
    try {
      URI uri = new URI(value);
      if (value.isEmpty()
          || value.codePointCount(0, value.length()) > 2048
          || !("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw new InvalidValue(Reason.INVALID_IMAGES);
      }
      return value;
    } catch (URISyntaxException exception) {
      throw new InvalidValue(Reason.INVALID_IMAGES);
    }
  }

  /**
   * 선택 원천 필드가 비었으면 null로 표현합니다.
   *
   * @param value 정리한 문자열
   * @return 선택 값 또는 null
   */
  private String optional(String value) {
    return value.isEmpty() ? null : value;
  }

  /**
   * 완전한 성공 원본의 구성 요소와 장소 식별자를 확인합니다.
   *
   * @param sourceId 저장 행의 공급원 장소 ID
   * @param payload 저장된 JSON
   * @throws InvalidValue 구조나 항목 ID가 잘못됐을 때
   */
  private void validatePayload(String sourceId, JsonNode payload) {
    if (!payload.isObject()
        || !payload.path("list").isObject()
        || sourceId == null
        || sourceId.isBlank()
        || !sourceId.equals(text(payload.path("list"), "contentid"))) {
      throw new InvalidValue(Reason.INVALID_PAYLOAD);
    }
    for (String field : new String[] {"common", "intro", "images"}) {
      JsonNode rows = payload.path(field);
      if (!rows.isArray() || (!field.equals("images") && rows.size() != 1)) {
        throw new InvalidValue(Reason.INVALID_PAYLOAD);
      }
      for (JsonNode row : rows) {
        if (!row.isObject() || !sourceId.equals(text(row, "contentid"))) {
          throw new InvalidValue(Reason.INVALID_PAYLOAD);
        }
      }
    }
  }

  /**
   * JSON 파싱 오류를 원문과 분리합니다.
   *
   * @param payload 원본 JSON
   * @return 파싱한 값
   * @throws InvalidValue 원본이 없거나 파싱할 수 없을 때
   */
  private JsonNode read(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new InvalidValue(Reason.INVALID_PAYLOAD);
    }
    try {
      return json.readTree(payload);
    } catch (JacksonException exception) {
      throw new InvalidValue(Reason.INVALID_PAYLOAD);
    }
  }

  /**
   * 문자열 또는 숫자의 앞뒤 공백만 제거합니다. 배열·객체를 문자열로 변환하지 않습니다.
   *
   * @param node 필드를 가진 객체
   * @param field 필드 이름
   * @return 스칼라 문자열, 누락되거나 null이면 빈 문자열
   * @throws InvalidValue 배열·객체·Boolean을 스칼라 필드에 전달했을 때
   */
  private String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return "";
    }
    if (!value.isString() && !value.isNumber()) {
      throw new InvalidValue(Reason.INVALID_PAYLOAD);
    }
    return value.asText().strip();
  }

  /**
   * 두 값이 같은 응답에 모두 있는지 확인합니다. 부분 쌍은 다른 응답과 합치지 않습니다.
   *
   * @param node 후보 응답 객체
   * @param first 첫 필드
   * @param second 둘째 필드
   * @return 두 값 모두 비어 있지 않은지 여부
   */
  private boolean hasPair(JsonNode node, String first, String second) {
    return !text(node, first).isEmpty() && !text(node, second).isEmpty();
  }

  /**
   * DB의 문자 수 제한을 UTF-16 길이가 아닌 Unicode code point 수로 검사합니다.
   *
   * @param value 정제한 문자열
   * @param maximum 최대 문자 수
   * @param reason 검증 실패 사유
   * @throws InvalidValue 필수 값이 없거나 너무 길 때
   */
  private void requireLength(String value, int maximum, Reason reason) {
    if (value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
      throw new InvalidValue(reason);
    }
  }

  /**
   * 원래 범위를 검사한 뒤 소수점 8자리로 반올림합니다. 국내 장소의 0 좌표는 미등록으로 처리합니다.
   *
   * @param value 좌표 스칼라 값
   * @param maximum 절댓값 허용 상한
   * @return 소수점 8자리 좌표
   * @throws InvalidValue 숫자가 아니거나 0 또는 허용 범위 밖일 때
   */
  private BigDecimal coordinate(String value, BigDecimal maximum) {
    try {
      BigDecimal number = new BigDecimal(value);
      if (number.signum() == 0 || number.abs().compareTo(maximum) > 0) {
        throw new InvalidValue(Reason.INVALID_COORDINATES);
      }
      BigDecimal rounded = number.setScale(8, RoundingMode.HALF_UP);
      if (rounded.signum() == 0) {
        throw new InvalidValue(Reason.INVALID_COORDINATES);
      }
      return rounded;
    } catch (NumberFormatException | ArithmeticException exception) {
      throw new InvalidValue(Reason.INVALID_COORDINATES);
    }
  }

  /**
   * 원본 내용 대신 원본 식별자만 결과에 전달합니다.
   *
   * @param raw 원본 스냅샷
   * @param status 정제 상태
   * @param reason 안전한 사유
   * @param place 정상 변환한 장소 값
   * @return 정제 결과
   */
  private PlaceNormalizationResult result(
      RawPlaceSnapshot raw, Status status, Reason reason, NormalizedPlace place) {
    return new PlaceNormalizationResult(raw.rawId(), raw.sourceId(), status, reason, place);
  }

  /** 원문 메시지를 포함하지 않는 내부 검증 중단 신호입니다. */
  private static final class InvalidValue extends RuntimeException {
    private final Reason reason;

    /**
     * 실패 분류만 보존합니다.
     *
     * @param reason 안전한 정제 실패 사유
     */
    private InvalidValue(Reason reason) {
      super(reason.name());
      this.reason = reason;
    }
  }
}
