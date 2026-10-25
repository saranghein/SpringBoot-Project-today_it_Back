package com.todayit.common.auth.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todayit.common.auth.handler.RestAccessDeniedHandler;
import com.todayit.common.auth.handler.RestAuthenticationEntryPoint;
import com.todayit.common.auth.jwt.JwtTokenProvider;
import com.todayit.common.auth.token.RefreshTokenService;
import com.todayit.common.pagination.PageResult;
import com.todayit.course.service.CoursePlaceQueryService;
import com.todayit.member.service.GoogleLoginService;
import com.todayit.member.service.LoginFacade;
import com.todayit.member.service.model.GoogleLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import com.todayit.place.exception.PlaceNotFoundException;
import com.todayit.place.service.PlaceCommandService;
import com.todayit.place.service.PlaceDetailFacade;
import com.todayit.place.service.PlaceQueryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Spring Security 접근 정책, 인증 인가 실패 응답 테스트
@ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "todayit.security.cors.origins[0]=https://frontend.test",
      "management.endpoints.web.exposure.include=health,prometheus"
    })
@AutoConfigureMockMvc
@Import({
  SecurityConfigTest.SecurityTestController.class,
  SecurityConfigTest.AdminSecurityTestConfig.class
})
public class SecurityConfigTest {

  private final MockMvc mockMvc;
  @MockitoBean private LoginFacade loginFacade;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private RefreshTokenService refreshTokenService;

  @MockitoBean private PlaceQueryService placeQueryService;

  @MockitoBean private PlaceDetailFacade placeDetailFacade;

  @MockitoBean private PlaceCommandService placeCommandService;

  @MockitoBean private CoursePlaceQueryService coursePlaceQueryService;

  @MockitoBean private GoogleLoginService googleLoginService;

  @Autowired
  SecurityConfigTest(MockMvc mockMvc) {
    this.mockMvc = mockMvc;
  }

  @Test
  @DisplayName("유효하지 않은 선택 토큰은 기존 정책에 따라 비회원으로 조회한다")
  void treatsInvalidOptionalTokenAsAnonymousForPlaceDetail() throws Exception {
    // Given
    when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
    doThrow(new PlaceNotFoundException()).when(placeDetailFacade).getPlaceDetail(1, null);
    // When
    ResultActions result =
        mockMvc.perform(
            get("/api/v1/places/1").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"));

    // Then
    result.andExpect(status().isNotFound());
    verify(placeDetailFacade).getPlaceDetail(1, null);
  }

  @Test
  @DisplayName("장소 상세는 인증 없이 조회할 수 있다")
  void allowsAnonymousPlaceDetailLookup() throws Exception {
    // Given
    doThrow(new PlaceNotFoundException()).when(placeDetailFacade).getPlaceDetail(1, null);
    // When
    ResultActions result = mockMvc.perform(get("/api/v1/places/1"));

    // Then
    result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    verify(placeDetailFacade).getPlaceDetail(1, null);
  }

  @Test
  @DisplayName("인증한 회원의 식별자로 장소 상세를 조회한다")
  @WithMockUser(username = "member-1")
  void passesAuthenticatedMemberToPlaceDetail() throws Exception {
    // Given
    doThrow(new PlaceNotFoundException()).when(placeDetailFacade).getPlaceDetail(1, "member-1");
    // When
    ResultActions result = mockMvc.perform(get("/api/v1/places/1"));

    // Then
    result.andExpect(status().isNotFound());
    verify(placeDetailFacade).getPlaceDetail(1, "member-1");
  }

  @Test
  @DisplayName("공개 API는 인증 없이 접근할 수 있다")
  void allowsAnonymousAccessToPublicEndpoint() throws Exception {
    // Given
    String url = "/api/v1/auth/login";

    MemberLoginResult member = new MemberLoginResult("member-1", "테스트", false, List.of("USER"));

    LoginResult loginResult = new LoginResult(member, "access-token", "refresh-token", 1800L);

    when(loginFacade.login(any())).thenReturn(loginResult);

    // When
    ResultActions result =
        mockMvc.perform(
            post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "email": "test@test.com",
                                      "password": "password"
                                    }
                                    """));

    // Then
    result.andExpect(status().isOk());
  }

  @Test
  @DisplayName("장소 사진 조회 API는 인증 없이 접근할 수 있다")
  void allowsAnonymousAccessToPlaceImagesEndpoint() throws Exception {
    // Given
    String url = "/api/v1/places/1/images";
    when(placeQueryService.findPlaceImages(anyInt(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0));

    // When
    ResultActions result = mockMvc.perform(get(url));

    // Then
    result.andExpect(status().isOk());
  }

  @Test
  @DisplayName("해당 장소의 코스 조회 API는 인증 없이 접근할 수 있다")
  void allowsAnonymousAccessToPlaceCoursesEndpoint() throws Exception {
    // Given
    String url = "/api/v1/places/1/courses";
    when(coursePlaceQueryService.findCoursesByPlace(anyInt(), anyInt(), anyInt(), any()))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0));

    // When
    ResultActions result = mockMvc.perform(get(url));

    // Then
    result.andExpect(status().isOk());
  }

  @Test
  @DisplayName("애플리케이션 liveness API는 인증 없이 접근할 수 있다")
  void allowsAnonymousAccessToLivenessEndpoint() throws Exception {
    // When
    ResultActions result = mockMvc.perform(get("/actuator/health/liveness"));

    // Then
    result.andExpect(status().isOk());
  }

  @Test
  @DisplayName("Prometheus는 인증 없이 실제 JVM 메트릭을 수집할 수 있다")
  void allowsAnonymousPrometheusScrape() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
  }

  @Test
  @DisplayName("Prometheus 수집 허용이 다른 Actuator 엔드포인트를 공개하지 않는다")
  void rejectsAnonymousAccessToOtherActuatorEndpoints() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("인증되지 않은 사용자가 보호 API에 접근하면 401을 반환한다")
  void returnsUnauthorizedWhenAnonymousUserAccessesProtectedEndpoint() throws Exception {
    // Given
    String url = "/api/v1/security/protected";

    // When
    ResultActions result = mockMvc.perform(get(url));

    // Then
    result
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
  }

  @Test
  @DisplayName("인증된 사용자가 권한이 없는 API에 접근하면 403을 반환한다")
  @WithMockUser(roles = "USER")
  void returnsForbiddenWhenAuthenticatedUserLacksRequiredRole() throws Exception {
    // Given
    String url = "/api/v1/security/admin/test";

    // When
    ResultActions result = mockMvc.perform(get(url));

    // Then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
  }

  // 허용된 Origin의 CORS preflight → 200 → Access-Control-Allow-Origin 확인
  @Test
  @DisplayName("허용된 Origin의 CORS preflight 요청은 허용한다")
  void allowsCorsPreflightRequestFromConfiguredOrigin() throws Exception {
    // Given
    String url = "/api/v1/auth/login";
    String origin = "https://frontend.test";

    // When
    ResultActions result =
        mockMvc.perform(
            options(url)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"));

    // Then
    result
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
  }

  // CorsProperties의 허용 목록 확인 -> 일치하지 않음 -> CORS 단계에서 차단 -> 403
  @Test
  @DisplayName("허용되지 않은 Origin의 CORS preflight 요청은 차단한다")
  void rejectsCorsPreflightRequestFromUnconfiguredOrigin() throws Exception {
    // Given
    String url = "/api/v1/auth/login";
    String origin = "https://not-allowed.test";

    // When
    ResultActions result =
        mockMvc.perform(
            options(url)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"));

    // Then
    result
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  // 테스트를 위해서 사용하는 임시 Controller
  @RestController
  static class SecurityTestController {
    // 401 응답 테스트
    @GetMapping("/api/v1/security/protected")
    ResponseEntity<Void> protectedEndpoint() {
      return ResponseEntity.ok().build();
    }

    // 403 응답 테스트
    @GetMapping("/api/v1/security/admin/test")
    ResponseEntity<Void> adminEndpoint() {
      return ResponseEntity.ok().build();
    }
  }

  // 403 응답 테스트를 위한 권한 제한 설정
  @TestConfiguration(proxyBeanMethods = false)
  static class AdminSecurityTestConfig {

    @Bean
    @Order(1) // 기존보다 먼저 검사
    SecurityFilterChain adminTestSecurityFilterChain(
        HttpSecurity http,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler)
        throws Exception {

      return http
          // SecurityFilterChain은 아래 경로에만 적용됩니다.
          .securityMatcher("/api/v1/security/admin/**")
          .csrf(csrf -> csrf.disable())

          // ADMIN 역할을 가진 사용자만 접근 가능
          .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ADMIN"))

          // 실제로 작성한 401/403 Handler가 호출되는지 함께 검증합니다.
          .exceptionHandling(
              exception ->
                  exception
                      .authenticationEntryPoint(authenticationEntryPoint)
                      .accessDeniedHandler(accessDeniedHandler))
          .build();
    }
  }

  @Test
  @DisplayName("유효한 Access Token과 로그인 세션이 있으면 보호 API에 접근할 수 있다")
  void allowsAccessWithValidAccessTokenAndActiveSession() throws Exception {
    // Given
    String url = "/api/v1/security/protected";
    String accessToken = "access-token";
    String memberId = "member-1";
    List<String> roles = List.of("USER");
    String sessionId = "session-1";

    when(jwtTokenProvider.validateToken(accessToken)).thenReturn(true);

    when(jwtTokenProvider.getMemberId(accessToken)).thenReturn(memberId);

    when(jwtTokenProvider.getSessionId(accessToken)).thenReturn(sessionId);

    when(refreshTokenService.isSessionActive(sessionId, memberId)).thenReturn(true);

    when(jwtTokenProvider.getRoles(accessToken)).thenReturn(roles);

    // When
    ResultActions result =
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

    // Then
    result.andExpect(status().isOk());
  }

  @Test
  @DisplayName("로그인 세션이 삭제된 Access Token으로 보호 API에 접근하면 401을 반환한다")
  void returnsUnauthorizedWhenLoginSessionIsDeleted() throws Exception {
    // Given
    String url = "/api/v1/security/protected";
    String accessToken = "access-token";
    String memberId = "member-1";
    String sessionId = "session-1";

    when(jwtTokenProvider.validateToken(accessToken)).thenReturn(true);

    when(jwtTokenProvider.getMemberId(accessToken)).thenReturn(memberId);

    when(jwtTokenProvider.getSessionId(accessToken)).thenReturn(sessionId);

    when(refreshTokenService.isSessionActive(sessionId, memberId)).thenReturn(false);

    // When
    ResultActions result =
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

    // Then
    result
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
  }

  @Test
  @DisplayName("Google 로그인 API는 Access Token 없이 호출할 수 있다")
  void allowsAnonymousGoogleLogin() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/google/callback";

    // 실제 Google 인증 대신 신규 사용자 결과를 반환하도록 설정
    when(googleLoginService.login("google-code", redirectUri))
        .thenReturn(GoogleLoginResult.newMember("signup-token"));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "google-code",
                                      "redirectUri": "http://localhost:3000/oauth/google/callback"
                                    }
                                    """));

    // Then
    // Authorization 헤더 없이도 Google 로그인 API에 접근 가능한지 확인
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.isNewMember").value(true))
        .andExpect(jsonPath("$.data.signupToken").value("signup-token"));
  }
}
