package com.todayit.place.service.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 정제와 저장에서 함께 사용하는 불변 장소 값입니다. 지역 ID와 서비스 장소 ID는 저장 시 결정합니다.
 *
 * @param name 장소명, 최대 50 Unicode code point
 * @param address 주소, 최대 255 Unicode code point
 * @param latitude 위도, 소수점 8자리
 * @param longitude 경도, 소수점 8자리
 * @param category 서비스 분류
 * @param sidoCode 법정동 시도 코드 2자리
 * @param sigunguCode 법정동 시군구 코드 3자리
 * @param sourceCategoryCode 원천 소분류 코드
 * @param description 일반 텍스트 소개, 없으면 null
 * @param images 정제한 원천 이미지. 저장 시 원본 URL만 반영하며 메타데이터는 원본 payload에 보존
 */
public record NormalizedPlace(
    String name,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    Category category,
    String sidoCode,
    String sigunguCode,
    String sourceCategoryCode,
    String description,
    List<Image> images) {

  /**
   * 이미지 후보 목록을 복사하여 후속 변경을 막습니다.
   *
   * @param name 장소명
   * @param address 주소
   * @param latitude 위도
   * @param longitude 경도
   * @param category 서비스 분류
   * @param sidoCode 시도 코드
   * @param sigunguCode 시군구 코드
   * @param sourceCategoryCode 원천 소분류
   * @param description 일반 텍스트 소개
   * @param images 이미지 후보
   */
  public NormalizedPlace {
    images = List.copyOf(images);
  }

  /** 정제된 장소의 서비스 카테고리입니다. */
  public enum Category {
    RESTAURANT,
    CAFE_DESSERT,
    EXHIBITION_MUSEUM,
    PERFORMANCE,
    EXPERIENCE,
    PLAY_LEISURE,
    NATURE_WALK,
    SHOPPING,
    LANDMARK_VIEW
  }

  /**
   * 다운로드·가공하지 않은 원천 이미지입니다. 저작권 코드 자체로 공개 이용 허가를 판정하지 않습니다.
   *
   * @param url 원본 HTTP(S) URL
   * @param thumbnailUrl 축소 이미지 URL, 없으면 null
   * @param name 원천 이미지명, 없으면 null
   * @param serialNumber 원천 이미지 일련번호, 없으면 null
   * @param copyrightCode 원천 저작권 구분, 없으면 null
   */
  public record Image(
      String url, String thumbnailUrl, String name, String serialNumber, String copyrightCode) {}
}
