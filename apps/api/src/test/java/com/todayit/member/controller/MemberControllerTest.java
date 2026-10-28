package com.todayit.member.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todayit.common.exception.GlobalExceptionHandler;
import com.todayit.common.exception.InvalidRequestException;
import com.todayit.common.pagination.PageResult;
import com.todayit.member.service.SignupService;
import com.todayit.place.entity.Category;
import com.todayit.place.service.PlaceScrapQueryService;
import com.todayit.place.service.model.PlaceResult;
import com.todayit.place.service.model.PlaceScrapSort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MemberControllerTest {

  @Mock private PlaceScrapQueryService placeScrapQueryService;
  @Mock private SignupService signupService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new MemberController(signupService, placeScrapQueryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("회원이 스크랩한 장소를 조회한다")
  void returnsScrappedPlaces() throws Exception {
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
            List.of("https://example.com/place.jpg"),
            false);
    when(placeScrapQueryService.findScrappedPlaces("member-1", 1, 20, PlaceScrapSort.OLDEST))
        .thenReturn(new PageResult<>(List.of(place), 1, 20, 21));

    // When
    mockMvc
        .perform(
            get("/api/v1/members/me/scrapped-places")
                .param("page", "1")
                .param("sort", "OLDEST")
                .principal(new UsernamePasswordAuthenticationToken("member-1", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].placeId").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.totalElements").value(21))
        .andExpect(jsonPath("$.data.totalPages").value(2));

    // Then
    verify(placeScrapQueryService).findScrappedPlaces("member-1", 1, 20, PlaceScrapSort.OLDEST);
  }

  @Test
  @DisplayName("이미 사용 중인 닉네임이면 available false를 반환한다")
  void returnsAvailableFalse() throws Exception {
    String nickname = "더미사용자";

    when(signupService.isNicknameAvailable(nickname)).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/members/nickname/check").param("nickname", nickname))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.available").value(false));

    verify(signupService).isNicknameAvailable(nickname);
  }

  @Test
  @DisplayName("닉네임 파라미터가 없으면 400을 반환한다")
  void rejectsMissingNickname() throws Exception {
    when(signupService.isNicknameAvailable(null))
        .thenThrow(new InvalidRequestException("닉네임은 필수입니다."));

    mockMvc
        .perform(get("/api/v1/members/nickname/check"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(signupService).isNicknameAvailable(null);
  }
}
