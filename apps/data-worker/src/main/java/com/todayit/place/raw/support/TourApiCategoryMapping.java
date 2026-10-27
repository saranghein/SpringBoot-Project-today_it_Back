package com.todayit.place.raw.support;

import com.todayit.place.service.model.NormalizedPlace.Category;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** TourAPI 소분류 코드를 서비스 카테고리에 매핑합니다. 접두사나 관광타입으로 분류를 추정하지 않습니다. */
public final class TourApiCategoryMapping {

  private static final Map<String, Category> ALLOWED = allowed();
  private static final Set<String> EXCLUDED =
      Set.of(
          "AC010100",
          "AC020100",
          "AC020200",
          "AC030100",
          "AC030200",
          "AC030300",
          "AC030400",
          "AC040100",
          "AC060100",
          "AC060200",
          "VE050200",
          "VE060200",
          "EV010100",
          "EV010200",
          "EV010300",
          "EV010400",
          "EV010500",
          "EV010600",
          "EV020100",
          "EV020200",
          "EV020300",
          "EV020400",
          "EV020500",
          "EV020600",
          "EV020700",
          "EV020800",
          "EV020900",
          "EV021000",
          "EV030100",
          "EV030200",
          "EV030300",
          "EV030400",
          "C0112001",
          "C0113001",
          "C0114001",
          "C0115001",
          "C0116001",
          "C0117001");

  private TourApiCategoryMapping() {}

  /**
   * 소분류 코드에 대응하는 서비스 카테고리를 조회합니다.
   *
   * @param code 신분류 소분류 코드
   * @return 서비스 카테고리, 미허용이면 빈 값
   */
  public static Optional<Category> findCategory(String code) {
    return Optional.ofNullable(ALLOWED.get(code));
  }

  /**
   * 명확히 서비스 대상이 아닌 코드인지 확인합니다. 미등록·검토 항목은 자동 제외로 추정하지 않습니다.
   *
   * @param code 신분류 소분류 코드
   * @return 명시적으로 제외한 코드인지 여부
   */
  public static boolean isExcluded(String code) {
    return EXCLUDED.contains(code);
  }

  /**
   * 소분류 코드와 서비스 카테고리의 매핑을 구성합니다.
   *
   * @return 변경 불가능한 허용 목록
   */
  private static Map<String, Category> allowed() {
    Map<String, Category> mapping = new HashMap<>();
    add(
        mapping,
        Category.RESTAURANT,
        "FD010100 FD010200 FD020100 FD020200 FD020300 FD020400 FD020500 FD030200 FD030300 FD030400 FD030500 FD030600 FD040100 FD040200 FD040400 FD040500");
    add(mapping, Category.CAFE_DESSERT, "FD030100 FD050100 FD050200 FD050300");
    add(mapping, Category.EXHIBITION_MUSEUM, "VE070100 VE070200 VE070300 VE070500 VE070600");
    add(mapping, Category.PERFORMANCE, "VE060100");
    add(
        mapping,
        Category.EXPERIENCE,
        "EX010100 EX020100 EX020200 EX020300 EX020400 EX030100 EX030200 EX030300 EX030400 EX040200");
    add(
        mapping,
        Category.PLAY_LEISURE,
        "LS010100 LS010200 LS010300 LS010400 LS010700 LS010800 LS010900 LS011000 LS011200 LS011300 LS011400 LS011500 LS011600 LS011700 LS011800 LS011900 LS020100 LS020200 LS020300 LS020400 LS020500 LS020600 LS020700 LS020800 LS020900 LS021000 LS021100 LS021200 LS021300 LS021400 LS030100 LS030200 LS030300 LS030400 LS030500 LS030600 LS040100 VE020100 VE020200");
    add(
        mapping,
        Category.NATURE_WALK,
        "NA010100 NA010200 NA010300 NA010400 NA010500 NA020100 NA020200 NA020300 NA020400 NA020500 NA020800 NA020900 NA030100 NA030300 NA030400 NA040100 NA040200 NA040300 NA040400 NA040500 NA040600 NA040700 VE030100 VE030200 VE030300 VE030400 VE030500 VE040300");
    add(
        mapping,
        Category.SHOPPING,
        "SH010100 SH020100 SH020200 SH030100 SH040100 SH040200 SH040300 SH050100 SH050200 SH050300 SH060100 SH060200 SH070100");
    add(
        mapping,
        Category.LANDMARK_VIEW,
        "HS010100 HS010200 HS010300 HS010400 HS010500 HS010600 HS010700 HS010800 HS010900 HS011000 HS011100 HS011200 HS020100 HS020200 HS020300 HS020400 VE010100 VE010200 VE010300 VE010400 VE010500 VE010600 VE010700 VE010800 VE010900");
    return Map.copyOf(mapping);
  }

  /**
   * 명시한 코드만 등록하고 중복 카테고리 지정은 초기화 오류로 처리합니다.
   *
   * @param mapping 구성 중인 허용 목록
   * @param category 서비스 카테고리
   * @param codes 공백으로 구분한 정확한 코드 목록
   * @throws IllegalStateException 같은 코드를 두 번 등록했을 때
   */
  private static void add(Map<String, Category> mapping, Category category, String codes) {
    for (String code : codes.split(" ")) {
      if (mapping.putIfAbsent(code, category) != null) {
        throw new IllegalStateException("신분류 코드가 중복 등록되었습니다.");
      }
    }
  }
}
