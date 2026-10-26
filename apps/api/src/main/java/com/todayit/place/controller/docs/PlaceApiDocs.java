package com.todayit.place.controller.docs;

import com.todayit.common.openapi.OpenApiConfig;
import com.todayit.common.pagination.PageResponse;
import com.todayit.common.response.CommonResponse;
import com.todayit.common.response.ErrorResponse;
import com.todayit.course.dto.response.CourseResponse;
import com.todayit.course.service.model.CourseSort;
import com.todayit.place.dto.response.PlaceDetailResponse;
import com.todayit.place.dto.response.PlaceFilterResponse;
import com.todayit.place.dto.response.PlaceImageResponse;
import com.todayit.place.dto.response.PlaceLikeResponse;
import com.todayit.place.dto.response.PlaceLocationResponse;
import com.todayit.place.dto.response.PlaceScrapResponse;
import com.todayit.place.entity.Category;
import com.todayit.place.service.model.PlaceSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/** 장소 조회와 스크랩 API 명세입니다. */
@Tag(name = "장소", description = "장소 조회와 스크랩 API")
public interface PlaceApiDocs {
  /**
   * 활성화되고 삭제되지 않은 장소의 상세 정보와 요청 회원의 좋아요·스크랩 여부를 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 선택 인증 정보, 비회원이면 null
   * @return 장소 기본 정보, 영업시간 목록, 이미지와 평균 체류 시간을 포함한 상세 응답
   */
  @Operation(
      summary = "장소 상세 조회",
      description = "장소의 상세 정보와 좋아요·스크랩 여부를 조회합니다. 인증은 선택사항입니다.",
      security = {@SecurityRequirement(), @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)})
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"placeId\":1,\"name\":\"오늘 카페\",\"category\":\"CAFE_DESSERT\",\"address\":\"서울특별시 종로구 종로 1\",\"location\":{\"latitude\":37.57,\"longitude\":126.985},\"description\":\"조용한 분위기의 카페입니다.\",\"openingHours\":[{\"openDate\":\"2026-10-16T00:00:00\",\"startAt\":\"2026-10-16T09:00:00\",\"endAt\":\"2026-10-16T18:00:00\",\"breakStartAt\":\"2026-10-16T12:00:00\",\"breakEndAt\":\"2026-10-16T13:00:00\"}],\"images\":[\"https://example.com/images/place-1.jpg\"],\"averageStayMinutes\":60,\"liked\":true,\"scrapped\":false}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "장소 식별자의 형식이 올바르지 않음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"요청값이 올바르지 않습니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소가 없거나 비활성·삭제 상태",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PlaceDetailResponse>> getPlaceDetail(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(hidden = true) Authentication authentication);

  /**
   * 장소 목록을 페이지 단위로 조회합니다.
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
  @Operation(
      summary = "장소 필터 조회",
      description =
          "지역·카테고리로 활성 장소를 페이지 단위로 조회합니다. sidoCode·sigunguCode 코드는 각각 선택사항이며, 전달된 조건을 모두 만족하는 지역의 장소를 조회합니다. 인증은 선택사항이며, 비회원의 liked는 false입니다.",
      security = {@SecurityRequirement(), @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)})
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"content\":[{\"placeId\":1,\"name\":\"오늘의 식당\",\"latitude\":37.57000000,\"longitude\":126.98500000,\"address\":\"서울특별시 종로구 종로 1\",\"category\":\"RESTAURANT\",\"viewCount\":15,\"liked\":false,\"imageUrls\":[\"https://example.com/place.jpg\"]}],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "잘못된 필터 또는 페이지 요청",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "잘못된 카테고리",
                      value =
                          "{\"success\":false,\"code\":\"INVALID_PLACE_CATEGORY\",\"message\":\"카테고리 필터 값이 올바르지 않습니다.\"}"),
                  @ExampleObject(
                      name = "잘못된 지역 코드",
                      value =
                          "{\"success\":false,\"code\":\"INVALID_REGION_PATH\",\"message\":\"지역 필터 값이 올바르지 않습니다.\"}"),
                  @ExampleObject(
                      name = "잘못된 페이지",
                      value =
                          "{\"success\":false,\"code\":\"INVALID_PAGINATION\",\"message\":\"페이지 요청값이 올바르지 않습니다.\"}")
                })),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PageResponse<PlaceFilterResponse>>> findPlaces(
      @Parameter(description = "시·도 코드 2자리입니다. 생략 가능합니다.", example = "41") String sidoCode,
      @Parameter(description = "시·군·구 코드 3자리입니다. 생략 가능합니다.", example = "110") String sigunguCode,
      @Parameter(
              description =
                  "카테고리를 복수 선택할 수 있습니다. 쉼표로 구분하며, 선택한 값 중 하나에 해당하는 장소를 조회합니다. 생략하면 전체 카테고리를 조회합니다.",
              examples = {
                @ExampleObject(name = "음식점", value = "[\"RESTAURANT\"]"),
                @ExampleObject(name = "카페·디저트", value = "[\"CAFE_DESSERT\"]"),
                @ExampleObject(name = "전시·박물관", value = "[\"EXHIBITION_MUSEUM\"]"),
                @ExampleObject(name = "공연", value = "[\"PERFORMANCE\"]"),
                @ExampleObject(name = "체험", value = "[\"EXPERIENCE\"]"),
                @ExampleObject(name = "놀이·레저", value = "[\"PLAY_LEISURE\"]"),
                @ExampleObject(name = "자연·산책", value = "[\"NATURE_WALK\"]"),
                @ExampleObject(name = "쇼핑", value = "[\"SHOPPING\"]"),
                @ExampleObject(name = "명소·전망", value = "[\"LANDMARK_VIEW\"]"),
                @ExampleObject(name = "음식점 + 카페·디저트", value = "[\"RESTAURANT\",\"CAFE_DESSERT\"]"),
                @ExampleObject(
                    name = "전시·박물관 + 공연",
                    value = "[\"EXHIBITION_MUSEUM\",\"PERFORMANCE\"]"),
                @ExampleObject(
                    name = "자연·산책 + 명소·전망",
                    value = "[\"NATURE_WALK\",\"LANDMARK_VIEW\"]")
              },
              array = @ArraySchema(schema = @Schema(implementation = Category.class)))
          List<String> categories,
      @Parameter(
              description = "페이지 번호입니다. 0부터 시작합니다.",
              example = "0",
              schema = @Schema(minimum = "0"))
          int page,
      @Parameter(
              description = "페이지 크기입니다. 1 이상이어야 합니다.",
              example = "20",
              schema = @Schema(minimum = "1"))
          int size,
      @Parameter(
              description = "정렬 기준입니다. LATEST는 최신순, POPULAR는 조회수순입니다.",
              examples = {
                @ExampleObject(name = "최신순", value = "LATEST"),
                @ExampleObject(name = "조회수순", value = "POPULAR")
              })
          PlaceSort sort,
      @Parameter(hidden = true) Authentication authentication);

  /**
   * 장소의 지도 표시 정보를 조회합니다.
   *
   * @param placeId 장소 식별자
   * @return 장소 위치 정보
   */
  @Operation(summary = "장소 지도 조회", description = "장소의 위도, 경도와 주소를 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"placeId\":1,\"latitude\":37.57000000,\"longitude\":126.98500000,\"address\":\"서울특별시 종로구 종로 1\"}}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PlaceLocationResponse>> findPlaceLocation(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId);

  /**
   * 장소의 사진 목록을 페이지 단위로 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @return 장소 사진 목록과 페이지 정보
   */
  @Operation(summary = "장소 사진 조회", description = "장소의 사진 목록을 페이지 단위로 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"content\":[{\"placeImageId\":10,\"imageUrl\":\"https://example.com/place-image.jpg\"}],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "잘못된 페이지 요청",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_PAGINATION\",\"message\":\"페이지 요청값이 올바르지 않습니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PageResponse<PlaceImageResponse>>> findPlaceImages(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(
              description = "페이지 번호입니다. 0부터 시작합니다.",
              example = "0",
              schema = @Schema(minimum = "0"))
          int page,
      @Parameter(
              description = "페이지 크기입니다. 1 이상이어야 합니다.",
              example = "20",
              schema = @Schema(minimum = "1"))
          int size);

  /**
   * 인증된 회원의 장소 스크랩을 생성합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 스크랩 결과
   */
  @Operation(
      summary = "장소 스크랩",
      description = "인증된 회원의 장소 스크랩을 생성합니다.",
      security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "스크랩 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"placeId\":1,\"scrapped\":true,\"scrapCount\":3}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "이미 스크랩한 장소입니다",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_ALREADY_SCRAPPED\",\"message\":\"이미 스크랩되었습니다.\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PlaceScrapResponse>> scrapPlace(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(hidden = true) Authentication authentication);

  /**
   * 인증된 회원의 장소 스크랩을 취소합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 스크랩 취소 결과
   */
  @Operation(
      summary = "장소 스크랩 취소",
      description = "인증된 회원의 장소 스크랩을 취소합니다.",
      security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "스크랩 취소 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"placeId\":1,\"scrapped\":false,\"scrapCount\":2}}"))),
    @ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PlaceScrapResponse>> cancelPlaceScrap(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(hidden = true) Authentication authentication);

  /**
   * 특정 장소의 공개 코스 목록을 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @param sort 정렬 기준
   * @return 장소를 포함한 공개 코스 목록과 페이지 정보
   */
  @Operation(summary = "장소별 코스 조회", description = "특정 장소를 포함한 공개 코스 목록을 페이지 단위로 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"content\":[{\"courseId\":1,\"title\":\"종로 맛집과 카페 코스\",\"courseSummary\":\"종로에서 식사와 카페를 함께 즐기는 코스입니다.\",\"transport\":\"WALKING\",\"startAt\":\"2026-10-03T11:00:00\",\"endAt\":\"2026-10-03T15:00:00\",\"viewCount\":25}],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "잘못된 페이지 요청",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_PAGINATION\",\"message\":\"페이지 요청값이 올바르지 않습니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PageResponse<CourseResponse>>> findCoursesByPlace(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(
              description = "페이지 번호입니다. 0부터 시작합니다.",
              example = "0",
              schema = @Schema(minimum = "0"))
          int page,
      @Parameter(
              description = "페이지 크기입니다. 1 이상이어야 합니다.",
              example = "20",
              schema = @Schema(minimum = "1"))
          int size,
      @Parameter(
              description = "정렬 기준입니다. LATEST는 최신순, POPULAR는 조회수순입니다.",
              examples = {
                @ExampleObject(name = "최신순", value = "LATEST"),
                @ExampleObject(name = "조회수순", value = "POPULAR")
              })
          CourseSort sort);

  /**
   * 인증된 회원의 장소 좋아요를 생성합니다.
   *
   * @param placeId 장소 식별자
   * @param authentication 인증된 회원 정보
   * @return 장소 좋아요 결과
   */
  @Operation(
      summary = "장소 좋아요",
      description = "인증된 회원의 장소 좋아요를 생성합니다.",
      security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "좋아요 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"placeId\":1,\"liked\":true,\"likeCount\":3}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "이미 좋아요를 누른 장소입니다",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_ALREADY_LIKED\",\"message\":\"이미 좋아요를 눌렀습니다.\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
    @ApiResponse(
        responseCode = "404",
        description = "장소를 찾을 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"PLACE_NOT_FOUND\",\"message\":\"장소를 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "서버 내부 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<PlaceLikeResponse>> likePlace(
      @Parameter(description = "장소 식별자입니다.", example = "1", required = true) int placeId,
      @Parameter(hidden = true) Authentication authentication);
}
