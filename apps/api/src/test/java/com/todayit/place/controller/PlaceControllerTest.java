package com.todayit.place.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todayit.common.exception.GlobalExceptionHandler;
import com.todayit.common.pagination.PageResult;
import com.todayit.course.service.CoursePlaceQueryService;
import com.todayit.course.service.model.CourseResult;
import com.todayit.course.service.model.CourseSort;
import com.todayit.place.entity.Category;
import com.todayit.place.exception.PlaceNotFoundException;
import com.todayit.place.service.PlaceCommandService;
import com.todayit.place.service.PlaceDetailFacade;
import com.todayit.place.service.PlaceQueryService;
import com.todayit.place.service.model.PlaceDetailResult;
import com.todayit.place.service.model.PlaceDetailWithStayResult;
import com.todayit.place.service.model.PlaceImageResult;
import com.todayit.place.service.model.PlaceLikeResult;
import com.todayit.place.service.model.PlaceLocationResult;
import com.todayit.place.service.model.PlaceOpeningHoursResult;
import com.todayit.place.service.model.PlaceResult;
import com.todayit.place.service.model.PlaceScrapResult;
import com.todayit.place.service.model.PlaceSort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PlaceControllerTest {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceControllerTest.class);

  @BeforeEach
  void logTestStart(TestInfo testInfo) {
    LOG.info("테스트 시작: {}", testInfo.getDisplayName());
  }

  @AfterEach
  void logTestEnd(TestInfo testInfo) {
    LOG.info("테스트 종료: {}", testInfo.getDisplayName());
  }

  @Mock private PlaceQueryService placeQueryService;

  @Mock private PlaceCommandService placeCommandService;

  @Mock private CoursePlaceQueryService coursePlaceQueryService;

  @Mock private PlaceDetailFacade placeDetailFacade;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlaceController(
                    placeQueryService,
                    placeCommandService,
                    coursePlaceQueryService,
                    placeDetailFacade))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("시도 코드만으로 조회한다")
  void forwardsSidoOnly() throws Exception {
    // Given
    when(placeQueryService.findPlaces("41", null, null, 0, 20, PlaceSort.LATEST, null))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0));

    // When & Then
    mockMvc.perform(get("/api/v1/places").param("sidoCode", "41")).andExpect(status().isOk());
    verify(placeQueryService).findPlaces("41", null, null, 0, 20, PlaceSort.LATEST, null);
  }

  @Test
  @DisplayName("시군구 코드만으로 조회한다")
  void forwardsSigunguOnly() throws Exception {
    // Given
    when(placeQueryService.findPlaces(null, "110", null, 0, 20, PlaceSort.LATEST, null))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0));

    // When & Then
    mockMvc.perform(get("/api/v1/places").param("sigunguCode", "110")).andExpect(status().isOk());
    verify(placeQueryService).findPlaces(null, "110", null, 0, 20, PlaceSort.LATEST, null);
  }

  @Test
  @DisplayName("지역 코드 두 조건을 모두 적용한다")
  void forwardsAllCodes() throws Exception {
    // Given
    when(placeQueryService.findPlaces("41", "111", null, 0, 20, PlaceSort.LATEST, null))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0));

    // When & Then
    mockMvc
        .perform(get("/api/v1/places").param("sidoCode", "41").param("sigunguCode", "111"))
        .andExpect(status().isOk());
    verify(placeQueryService).findPlaces("41", "111", null, 0, 20, PlaceSort.LATEST, null);
  }

  @Test
  @DisplayName("인증된 회원의 장소 목록에 좋아요 여부를 반환한다")
  void returnsAuthenticatedMemberLikesInList() throws Exception {
    PlaceResult place =
        new PlaceResult(
            1,
            "Liked",
            BigDecimal.ONE,
            BigDecimal.ONE,
            "Address",
            Category.RESTAURANT,
            0,
            List.of(),
            true);
    when(placeQueryService.findPlaces(null, null, null, 0, 20, PlaceSort.LATEST, "member-1"))
        .thenReturn(new PageResult<>(List.of(place), 0, 20, 1));

    mockMvc
        .perform(
            get("/api/v1/places")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null, List.of())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].liked").value(true));
    verify(placeQueryService).findPlaces(null, null, null, 0, 20, PlaceSort.LATEST, "member-1");
  }

  @Test
  @DisplayName("장소 상세에 영업시간 리스트와 평균 체류 시간을 반환한다")
  void returnsPlaceDetailWithOpeningHoursList() throws Exception {
    // Given
    PlaceOpeningHoursResult opening =
        new PlaceOpeningHoursResult(
            LocalDateTime.of(2026, 10, 16, 0, 0),
            LocalDateTime.of(2026, 10, 16, 9, 0),
            LocalDateTime.of(2026, 10, 16, 18, 0),
            null,
            null);
    PlaceResult place =
        new PlaceResult(
            1,
            "카페",
            new BigDecimal("37.5"),
            new BigDecimal("127.0"),
            "서울",
            Category.CAFE_DESSERT,
            0,
            List.of("https://example.com/place.jpg"),
            false);
    PlaceDetailResult detail =
        new PlaceDetailResult(place, "장소 설명", List.of(opening), false, false);
    when(placeDetailFacade.getPlaceDetail(1, null))
        .thenReturn(new PlaceDetailWithStayResult(detail, 0));
    // When
    ResultActions result = mockMvc.perform(get("/api/v1/places/1"));

    // Then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.placeId").value(1))
        .andExpect(jsonPath("$.data.location.latitude").value(37.5))
        .andExpect(jsonPath("$.data.description").value("장소 설명"))
        .andExpect(jsonPath("$.data.openingHours").isArray())
        .andExpect(jsonPath("$.data.openingHours[0].startAt").value("2026-10-16T09:00:00"))
        .andExpect(jsonPath("$.data.images[0]").value("https://example.com/place.jpg"))
        .andExpect(jsonPath("$.data.averageStayMinutes").value(0))
        .andExpect(jsonPath("$.data.liked").value(false))
        .andExpect(jsonPath("$.data.scrapped").value(false));
  }

  @Test
  @DisplayName("선택 인증의 회원 식별자를 상세 조회에 전달한다")
  void passesOptionalMemberToDetailLookup() throws Exception {
    // Given
    doThrow(new PlaceNotFoundException()).when(placeDetailFacade).getPlaceDetail(1, "member-1");
    // When
    ResultActions result =
        mockMvc.perform(
            get("/api/v1/places/1")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null, List.of())));

    // Then
    result.andExpect(status().isNotFound());
    verify(placeDetailFacade).getPlaceDetail(1, "member-1");
  }

  @Test
  @DisplayName("장소 목록을 페이지 정보와 함께 반환한다")
  void returnsPagedPlaces() throws Exception {
    // Given
    PlaceResult place =
        new PlaceResult(
            1,
            "오늘의 식당",
            new BigDecimal("37.5"),
            new BigDecimal("127.0"),
            "서울특별시 종로구 종로 1",
            Category.RESTAURANT,
            10,
            List.of("https://placehold.co/1200x800?text=Restaurant"),
            false);
    when(placeQueryService.findPlaces(
            "11",
            "110",
            List.of(Category.RESTAURANT, Category.CAFE_DESSERT),
            1,
            5,
            PlaceSort.POPULAR,
            null))
        .thenReturn(new PageResult<>(List.of(place), 1, 5, 6));

    // When
    mockMvc
        .perform(
            get("/api/v1/places")
                .param("sidoCode", "11")
                .param("sigunguCode", "110")
                .param("categories", "RESTAURANT", "CAFE_DESSERT")
                .param("page", "1")
                .param("size", "5")
                .param("sort", "POPULAR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].placeId").value(1))
        .andExpect(jsonPath("$.data.content[0].liked").value(false))
        .andExpect(jsonPath("$.data.content[0].name").value("오늘의 식당"))
        .andExpect(jsonPath("$.data.content[0].address").value("서울특별시 종로구 종로 1"))
        .andExpect(
            jsonPath("$.data.content[0].imageUrls[0]")
                .value("https://placehold.co/1200x800?text=Restaurant"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(5))
        .andExpect(jsonPath("$.data.totalElements").value(6))
        .andExpect(jsonPath("$.data.totalPages").value(2));

    // Then
    verify(placeQueryService)
        .findPlaces(
            "11",
            "110",
            List.of(Category.RESTAURANT, Category.CAFE_DESSERT),
            1,
            5,
            PlaceSort.POPULAR,
            null);
  }

  @Test
  @DisplayName("술집은 독립 카테고리 필터로 받지 않는다")
  void rejectsRemovedBarCategory() throws Exception {
    // When & Then
    mockMvc
        .perform(get("/api/v1/places").param("categories", "BAR"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PLACE_CATEGORY"));
    verifyNoInteractions(placeQueryService);
  }

  @Test
  @DisplayName("기존 영화·공연 카테고리 필터는 받지 않는다")
  void rejectsRemovedMovieCategory() throws Exception {
    // When & Then
    mockMvc
        .perform(get("/api/v1/places").param("categories", "MOVIE_PERFORMANCE"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PLACE_CATEGORY"));
    verifyNoInteractions(placeQueryService);
  }

  @Test
  @DisplayName("스파 카테고리 필터는 받지 않는다")
  void rejectsRemovedSpaCategory() throws Exception {
    // When & Then
    mockMvc
        .perform(get("/api/v1/places").param("categories", "RELAXATION_SPA"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PLACE_CATEGORY"));
    verifyNoInteractions(placeQueryService);
  }

  @Test
  @DisplayName("지원하지 않는 카테고리 필터는 400 응답을 반환한다")
  void returnsBadRequestForInvalidCategory() throws Exception {
    // When
    mockMvc
        .perform(get("/api/v1/places").param("categories", "INVALID_CATEGORY"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_PLACE_CATEGORY"))
        .andExpect(jsonPath("$.message").value("카테고리 필터 값이 올바르지 않습니다."));

    // Then
    verifyNoInteractions(placeQueryService);
  }

  @Test
  @DisplayName("빈 카테고리 필터는 400 응답을 반환한다")
  void returnsBadRequestForEmptyCategory() throws Exception {
    // When
    mockMvc
        .perform(get("/api/v1/places").param("categories", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_PLACE_CATEGORY"))
        .andExpect(jsonPath("$.message").value("카테고리 필터 값이 올바르지 않습니다."));

    // Then
    verifyNoInteractions(placeQueryService);
  }

  @Test
  @DisplayName("해당 장소로 만들어진 코스를 페이지 정보와 함께 반환한다")
  void returnsCoursesByPlace() throws Exception {
    // Given
    CourseResult course =
        new CourseResult(
            1,
            "종로 맛집과 카페 코스",
            "종로에서 식사와 카페를 함께 즐기는 코스입니다.",
            com.todayit.course.entity.CourseTransport.WALKING,
            LocalDateTime.of(2026, 10, 3, 11, 0),
            LocalDateTime.of(2026, 10, 3, 15, 0),
            25);
    when(coursePlaceQueryService.findCoursesByPlace(1, 0, 20, CourseSort.LATEST))
        .thenReturn(new PageResult<>(List.of(course), 0, 20, 1));

    // When
    mockMvc
        .perform(get("/api/v1/places/1/courses"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].courseId").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("종로 맛집과 카페 코스"))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.totalPages").value(1));

    // Then
    verify(coursePlaceQueryService).findCoursesByPlace(1, 0, 20, CourseSort.LATEST);
  }

  @Test
  @DisplayName("존재하지 않는 장소의 코스 조회는 장소 없음 오류를 반환한다")
  void returnsNotFoundWhenCoursesPlaceDoesNotExist() throws Exception {
    // Given
    doThrow(new PlaceNotFoundException()).when(placeQueryService).validatePlaceExists(999);

    // When
    mockMvc
        .perform(get("/api/v1/places/999/courses"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

    // Then
    verifyNoInteractions(coursePlaceQueryService);
  }

  @Test
  @DisplayName("장소 사진 목록을 페이지 정보와 함께 반환한다")
  void returnsPagedPlaceImages() throws Exception {
    // Given
    when(placeQueryService.findPlaceImages(1, 0, 5))
        .thenReturn(
            new PageResult<>(
                List.of(new PlaceImageResult(10, "https://example.com/place-image.jpg")), 0, 5, 1));

    // When
    mockMvc
        .perform(get("/api/v1/places/1/images").param("page", "0").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].placeImageId").value(10))
        .andExpect(
            jsonPath("$.data.content[0].imageUrl").value("https://example.com/place-image.jpg"))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(5))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.totalPages").value(1));

    // Then
    verify(placeQueryService).findPlaceImages(1, 0, 5);
  }

  @Test
  @DisplayName("장소를 스크랩하고 스크랩 수를 반환한다")
  void scrapsPlace() throws Exception {
    // Given
    when(placeCommandService.scrapPlace(1, "member-1"))
        .thenReturn(new PlaceScrapResult(1, true, 3));

    // When
    mockMvc
        .perform(
            post("/api/v1/places/1/scrap")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.placeId").value(1))
        .andExpect(jsonPath("$.data.scrapped").value(true))
        .andExpect(jsonPath("$.data.scrapCount").value(3));

    // Then
    verify(placeCommandService).scrapPlace(1, "member-1");
  }

  @Test
  @DisplayName("장소를 좋아요하고 좋아요 수를 반환한다")
  void likesPlace() throws Exception {
    // Given
    when(placeCommandService.likePlace(1, "member-1")).thenReturn(new PlaceLikeResult(1, true, 3));

    // When
    mockMvc
        .perform(
            post("/api/v1/places/1/like")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.placeId").value(1))
        .andExpect(jsonPath("$.data.liked").value(true))
        .andExpect(jsonPath("$.data.likeCount").value(3));

    // Then
    verify(placeCommandService).likePlace(1, "member-1");
  }

  @Test
  @DisplayName("장소 스크랩을 취소하고 남은 스크랩 수를 반환한다")
  void cancelsPlaceScrap() throws Exception {
    // Given
    when(placeCommandService.cancelPlaceScrap(1, "member-1"))
        .thenReturn(new PlaceScrapResult(1, false, 2));

    // When
    mockMvc
        .perform(
            delete("/api/v1/places/1/scrap")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.placeId").value(1))
        .andExpect(jsonPath("$.data.scrapped").value(false))
        .andExpect(jsonPath("$.data.scrapCount").value(2));

    // Then
    verify(placeCommandService).cancelPlaceScrap(1, "member-1");
  }

  @Test
  @DisplayName("페이지 번호가 음수이면 400 응답을 반환한다")
  void returnsBadRequestWhenPageIsNegative() throws Exception {
    // Given
    String invalidPage = "-1";

    // When
    mockMvc
        .perform(get("/api/v1/places").param("page", invalidPage))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));

    // Then
    verifyNoInteractions(placeQueryService, placeCommandService);
  }

  @Test
  @DisplayName("페이지 크기가 0 이하이면 장소 전용 커스텀 오류를 반환한다")
  void returnsPlaceCustomErrorWhenSizeIsNotPositive() throws Exception {
    // Given
    String invalidSize = "0";

    // When
    mockMvc
        .perform(get("/api/v1/places").param("size", invalidSize))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"))
        .andExpect(jsonPath("$.message").value("페이지 요청값이 올바르지 않습니다."));

    // Then
    verifyNoInteractions(placeQueryService, placeCommandService);
  }

  @Test
  @DisplayName("장소 지도 정보를 반환한다")
  void returnsPlaceLocation() throws Exception {
    // Given
    when(placeQueryService.findPlaceLocation(1))
        .thenReturn(
            new PlaceLocationResult(
                1,
                new BigDecimal("37.57000000"),
                new BigDecimal("126.98500000"),
                "서울특별시 종로구 종로 1"));

    // When
    mockMvc
        .perform(get("/api/v1/places/1/location"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.placeId").value(1))
        .andExpect(jsonPath("$.data.latitude").value(37.57))
        .andExpect(jsonPath("$.data.longitude").value(126.985))
        .andExpect(jsonPath("$.data.address").value("서울특별시 종로구 종로 1"));

    // Then
    verify(placeQueryService).findPlaceLocation(1);
  }

  @Test
  @DisplayName("존재하지 않는 장소의 지도 조회는 404 응답을 반환한다")
  void returnsNotFoundWhenPlaceLocationDoesNotExist() throws Exception {
    // Given
    when(placeQueryService.findPlaceLocation(999)).thenThrow(new PlaceNotFoundException());

    // When
    mockMvc
        .perform(get("/api/v1/places/999/location"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

    // Then
    verify(placeQueryService).findPlaceLocation(999);
  }
}
