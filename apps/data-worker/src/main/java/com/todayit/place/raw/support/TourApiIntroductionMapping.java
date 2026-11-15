package com.todayit.place.raw.support;

import static java.util.Map.entry;

import com.todayit.place.service.model.NormalizedPlace.IntroductionField;
import java.util.EnumMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** TourAPI 소개 상세의 필드를 관광타입별로 일반 텍스트로 정제합니다. */
public final class TourApiIntroductionMapping {

  private static final Map<String, Map<IntroductionField, String>> SOURCE_FIELDS =
      Map.of(
          "12",
          Map.ofEntries(
              entry(IntroductionField.PARKING, "parking"),
              entry(IntroductionField.PET_ACCESS, "chkpet"),
              entry(IntroductionField.STROLLER_RENTAL, "chkbabycarriage"),
              entry(IntroductionField.CARD_PAYMENT, "chkcreditcard"),
              entry(IntroductionField.EXPERIENCE_GUIDE, "expguide"),
              entry(IntroductionField.EXPERIENCE_AGE, "expagerange"),
              entry(IntroductionField.OPENING_HOURS, "usetime"),
              entry(IntroductionField.OPEN_SEASON, "useseason"),
              entry(IntroductionField.CLOSED_DAYS, "restdate")),
          "14",
          Map.ofEntries(
              entry(IntroductionField.PARKING, "parkingculture"),
              entry(IntroductionField.PARKING_FEE, "parkingfee"),
              entry(IntroductionField.PET_ACCESS, "chkpetculture"),
              entry(IntroductionField.STROLLER_RENTAL, "chkbabycarriageculture"),
              entry(IntroductionField.CARD_PAYMENT, "chkcreditcardculture"),
              entry(IntroductionField.OPENING_HOURS, "usetimeculture"),
              entry(IntroductionField.CLOSED_DAYS, "restdateculture"),
              entry(IntroductionField.ADMISSION_FEE, "usefee"),
              entry(IntroductionField.DISCOUNT, "discountinfo"),
              entry(IntroductionField.VISIT_DURATION, "spendtime")),
          "28",
          Map.ofEntries(
              entry(IntroductionField.PARKING, "parkingleports"),
              entry(IntroductionField.PARKING_FEE, "parkingfeeleports"),
              entry(IntroductionField.PET_ACCESS, "chkpetleports"),
              entry(IntroductionField.STROLLER_RENTAL, "chkbabycarriageleports"),
              entry(IntroductionField.CARD_PAYMENT, "chkcreditcardleports"),
              entry(IntroductionField.RESERVATION, "reservation"),
              entry(IntroductionField.OPENING_HOURS, "usetimeleports"),
              entry(IntroductionField.CLOSED_DAYS, "restdateleports"),
              entry(IntroductionField.ADMISSION_FEE, "usefeeleports"),
              entry(IntroductionField.EXPERIENCE_AGE, "expagerangeleports")),
          "38",
          Map.ofEntries(
              entry(IntroductionField.PARKING, "parkingshopping"),
              entry(IntroductionField.RESTROOM, "restroom"),
              entry(IntroductionField.PET_ACCESS, "chkpetshopping"),
              entry(IntroductionField.STROLLER_RENTAL, "chkbabycarriageshopping"),
              entry(IntroductionField.CARD_PAYMENT, "chkcreditcardshopping"),
              entry(IntroductionField.OPENING_HOURS, "opentime"),
              entry(IntroductionField.CLOSED_DAYS, "restdateshopping"),
              entry(IntroductionField.MARKET_DAYS, "fairday"),
              entry(IntroductionField.SHOP_GUIDE, "shopguide")),
          "39",
          Map.ofEntries(
              entry(IntroductionField.PARKING, "parkingfood"),
              entry(IntroductionField.CARD_PAYMENT, "chkcreditcardfood"),
              entry(IntroductionField.TAKEOUT, "packing"),
              entry(IntroductionField.RESERVATION, "reservationfood"),
              entry(IntroductionField.CHILDREN_PLAYROOM, "kidsfacility"),
              entry(IntroductionField.SMOKING_POLICY, "smoking"),
              entry(IntroductionField.SEAT_COUNT, "seat"),
              entry(IntroductionField.OPENING_HOURS, "opentimefood"),
              entry(IntroductionField.CLOSED_DAYS, "restdatefood")));

  private TourApiIntroductionMapping() {}

  /**
   * 소개 필드의 HTML과 공백을 정리하고 표시할 내용이 있는 값만 반환합니다.
   *
   * @param contentTypeId 목록의 원천 관광타입
   * @param intro 소개 상세 객체
   * @return 정제한 편의시설·서비스·운영 안내. 미지원 타입은 빈 맵
   */
  public static Map<IntroductionField, String> normalize(String contentTypeId, JsonNode intro) {
    Map<IntroductionField, String> values = new EnumMap<>(IntroductionField.class);
    SOURCE_FIELDS
        .getOrDefault(contentTypeId, Map.of())
        .forEach(
            (field, sourceField) -> {
              JsonNode sourceValue = intro.path(sourceField);
              if (sourceValue.isString() || sourceValue.isNumber()) {
                String value = PlaceDescriptionText.fromHtml(sourceValue.asText());
                if (value != null) {
                  values.put(field, value);
                }
              }
            });
    return Map.copyOf(values);
  }
}
