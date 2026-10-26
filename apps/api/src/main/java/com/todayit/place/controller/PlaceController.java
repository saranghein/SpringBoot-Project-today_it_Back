package com.todayit.place.controller;

import com.todayit.common.pagination.PageResponse;
import com.todayit.common.pagination.PaginationValidator;
import com.todayit.common.response.CommonResponse;
import com.todayit.course.dto.response.CourseResponse;
import com.todayit.course.service.CoursePlaceQueryService;
import com.todayit.course.service.model.CourseSort;
import com.todayit.place.controller.docs.PlaceApiDocs;
import com.todayit.place.dto.response.PlaceDetailResponse;
import com.todayit.place.dto.response.PlaceFilterResponse;
import com.todayit.place.dto.response.PlaceImageResponse;
import com.todayit.place.dto.response.PlaceLikeResponse;
import com.todayit.place.dto.response.PlaceLocationResponse;
import com.todayit.place.dto.response.PlaceScrapResponse;
import com.todayit.place.entity.Category;
import com.todayit.place.exception.InvalidPlaceCategoryFilterException;
import com.todayit.place.service.PlaceCommandService;
import com.todayit.place.service.PlaceDetailFacade;
import com.todayit.place.service.PlaceQueryService;
import com.todayit.place.service.model.PlaceSort;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 장소 관련 HTTP 요청을 처리하는 Controller입니다. */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController implements PlaceApiDocs {

  private static final int DEFAULT_PAGE = 0;
  private static final int DEFAULT_SIZE = 20;
  private static final Set<String> CATEGORY_FILTER_VALUES =
      Arrays.stream(Category.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  private final PlaceDetailFacade placeDetailFacade;
  private final PlaceQueryService placeQueryService;
  private final PlaceCommandService placeCommandService;
  private final CoursePlaceQueryService coursePlaceQueryService;

  /**
   * 장소 기능을 처리할 서비스를 받습니다.
   *
   * @param placeDetailFacade 장소 상세 조회 Facade
   * @param placeQueryService 장소 조회 Service
   * @param placeCommandService 장소 변경 Service
   * @param coursePlaceQueryService 코스 장소 조회 Service 계약
   */
  public PlaceController(
      PlaceQueryService placeQueryService,
      PlaceCommandService placeCommandService,
      CoursePlaceQueryService coursePlaceQueryService,
      PlaceDetailFacade placeDetailFacade) {
    this.placeDetailFacade = placeDetailFacade;
    this.placeQueryService = placeQueryService;
    this.placeCommandService = placeCommandService;
    this.coursePlaceQueryService = coursePlaceQueryService;
  }

  /**
   * 장소 상세를 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 선택 인증 정보
   * @return 장소 상세 응답
   */
  @GetMapping("/{placeId}")
  public ResponseEntity<CommonResponse<PlaceDetailResponse>> getPlaceDetail(
      @PathVariable int placeId, Authentication authentication) {
    String memberId = authentication == null ? null : authentication.getName();
    return ResponseEntity.ok(
        CommonResponse.success(
            PlaceDetailResponse.from(placeDetailFacade.getPlaceDetail(placeId, memberId))));
  }

  /**
   * 활성화되고 삭제되지 않은 장소 목록을 조회합니다.
   *
   * @param authentication 선택 인증 정보
   * @param sidoCode 시·도 코드 2자리, 생략 가능
   * @param sigunguCode 시·군·구 코드 3자리, 생략 가능
   * @param categories 장소 카테고리 목록
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @param sort 정렬 기준
   * @return 장소 목록과 페이지 정보
   */
  @GetMapping
  public ResponseEntity<CommonResponse<PageResponse<PlaceFilterResponse>>> findPlaces(
      @RequestParam(required = false) String sidoCode,
      @RequestParam(required = false) String sigunguCode,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
      @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
      @RequestParam(defaultValue = "LATEST") PlaceSort sort,
      Authentication authentication) {
    PaginationValidator.validate(page, size);
    List<Category> categoryFilters = parseCategories(categories);
    return ResponseEntity.ok(
        CommonResponse.success(
            PageResponse.from(
                placeQueryService.findPlaces(
                    sidoCode,
                    sigunguCode,
                    categoryFilters,
                    page,
                    size,
                    sort,
                    authentication == null ? null : authentication.getName()),
                PlaceFilterResponse::from)));
  }

  /**
   * 요청 카테고리 값을 장소 카테고리 목록으로 변환합니다.
   *
   * @param categories 요청 카테고리 값 목록
   * @return 변환한 장소 카테고리 목록, 파라미터를 생략하면 {@code null}
   * @throws InvalidPlaceCategoryFilterException 빈 값이거나 지원하지 않는 카테고리일 때
   */
  private List<Category> parseCategories(List<String> categories) {
    if (categories == null) {
      return null;
    }
    if (categories.isEmpty()
        || categories.stream()
            .anyMatch(
                category -> category.isBlank() || !CATEGORY_FILTER_VALUES.contains(category))) {
      throw new InvalidPlaceCategoryFilterException();
    }

    return categories.stream().map(Category::valueOf).toList();
  }

  /**
   * 장소의 지도 표시 정보를 조회합니다.
   *
   * @param placeId 장소 식별자
   * @return 장소 위치 응답
   */
  @GetMapping("/{placeId}/location")
  public ResponseEntity<CommonResponse<PlaceLocationResponse>> findPlaceLocation(
      @PathVariable int placeId) {
    return ResponseEntity.ok(
        CommonResponse.success(
            PlaceLocationResponse.from(placeQueryService.findPlaceLocation(placeId))));
  }

  /**
   * 장소의 사진 목록을 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @return 장소 사진 목록과 페이지 정보
   */
  @GetMapping("/{placeId}/images")
  public ResponseEntity<CommonResponse<PageResponse<PlaceImageResponse>>> findPlaceImages(
      @PathVariable int placeId,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
      @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
    PaginationValidator.validate(page, size);
    return ResponseEntity.ok(
        CommonResponse.success(
            PageResponse.from(
                placeQueryService.findPlaceImages(placeId, page, size), PlaceImageResponse::from)));
  }

  /**
   * 특정 장소를 포함한 공개 코스 목록을 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @param sort 코스 정렬 기준
   * @return 코스 목록과 페이지 정보
   */
  @GetMapping("/{placeId}/courses")
  public ResponseEntity<CommonResponse<PageResponse<CourseResponse>>> findCoursesByPlace(
      @PathVariable int placeId,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
      @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
      @RequestParam(defaultValue = "LATEST") CourseSort sort) {
    PaginationValidator.validate(page, size);
    placeQueryService.validatePlaceExists(placeId);
    return ResponseEntity.ok(
        CommonResponse.success(
            PageResponse.from(
                coursePlaceQueryService.findCoursesByPlace(placeId, page, size, sort),
                CourseResponse::from)));
  }

  /**
   * 인증된 회원의 장소 스크랩을 생성합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 스크랩 결과
   */
  @PostMapping("/{placeId}/scrap")
  public ResponseEntity<CommonResponse<PlaceScrapResponse>> scrapPlace(
      @PathVariable int placeId, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            CommonResponse.success(
                PlaceScrapResponse.from(
                    placeCommandService.scrapPlace(placeId, authentication.getName()))));
  }

  /**
   * 인증된 회원의 장소 스크랩을 취소합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 스크랩 결과
   */
  @DeleteMapping("/{placeId}/scrap")
  public ResponseEntity<CommonResponse<PlaceScrapResponse>> cancelPlaceScrap(
      @PathVariable int placeId, Authentication authentication) {
    return ResponseEntity.ok(
        CommonResponse.success(
            PlaceScrapResponse.from(
                placeCommandService.cancelPlaceScrap(placeId, authentication.getName()))));
  }

  /**
   * 인증된 회원의 장소 좋아요를 생성합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 좋아요 결과
   */
  @PostMapping("/{placeId}/like")
  public ResponseEntity<CommonResponse<PlaceLikeResponse>> likePlace(
      @PathVariable int placeId, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            CommonResponse.success(
                PlaceLikeResponse.from(
                    placeCommandService.likePlace(placeId, authentication.getName()))));
  }
}
