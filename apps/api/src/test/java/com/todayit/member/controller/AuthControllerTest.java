package com.todayit.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todayit.common.exception.BusinessException;
import com.todayit.common.exception.GlobalExceptionHandler;
import com.todayit.member.dto.request.EmailVerificationConfirmRequest;
import com.todayit.member.dto.request.EmailVerificationRequest;
import com.todayit.member.dto.request.LoginRequest;
import com.todayit.member.dto.request.SignupRequest;
import com.todayit.member.entity.EmailVerificationPurpose;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.exception.LoginFailedException;
import com.todayit.member.exception.LoginLockedException;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.service.EmailVerificationService;
import com.todayit.member.service.GoogleLoginService;
import com.todayit.member.service.KakaoLoginService;
import com.todayit.member.service.LoginFacade;
import com.todayit.member.service.LogoutService;
import com.todayit.member.service.SignupFacade;
import com.todayit.member.service.SignupService;
import com.todayit.member.service.TokenRefreshService;
import com.todayit.member.service.model.EmailVerificationConfirmResult;
import com.todayit.member.service.model.GoogleLoginResult;
import com.todayit.member.service.model.KakaoLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import com.todayit.member.service.model.TokenRefreshResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

  @Mock private LoginFacade loginFacade;

  @Mock private LogoutService logoutService;

  @Mock private SignupService signupService;

  @Mock private EmailVerificationService emailVerificationService;

  @Mock private TokenRefreshService tokenRefreshService;

  @Mock private GoogleLoginService googleLoginService;

  @Mock private KakaoLoginService kakaoLoginService;

  @Mock private SignupFacade signupFacade;

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    AuthController authController =
        new AuthController(
            loginFacade,
            logoutService,
            signupService,
            emailVerificationService,
            tokenRefreshService,
            googleLoginService,
            kakaoLoginService,
            signupFacade);

    mockMvc =
        MockMvcBuilders.standaloneSetup(authController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    objectMapper = new ObjectMapper();
  }

  @Test
  @DisplayName("로그인에 성공하면 회원 정보와 Access Token, Refresh Token을 반환한다")
  void returnsTokensAfterSuccessfulLogin() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@test.com", "password");

    MemberLoginResult member =
        new MemberLoginResult("member-1", "테스트", false, List.of("DEV", "USER"));

    LoginResult result = new LoginResult(member, "access-token", "refresh-token", 1800L);

    when(loginFacade.login(any())).thenReturn(result);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").value("access-token"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(jsonPath("$.data.member.memberId").value("member-1"))
        .andExpect(jsonPath("$.data.member.nickname").value("테스트"))
        .andExpect(jsonPath("$.data.member.coupleConnected").value(false))
        .andExpect(jsonPath("$.data.member.roles[0]").value("DEV"))
        .andExpect(jsonPath("$.data.member.roles[1]").value("USER"));

    verify(loginFacade).login(any());
  }

  @Test
  @DisplayName("로그인에 실패하면 현재 실패 횟수를 반환한다")
  void returnsFailureCountWhenLoginFails() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@test.com", "wrong-password");

    when(loginFacade.login(any())).thenThrow(new LoginFailedException(3));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
        .andExpect(jsonPath("$.failureCount").value(3))
        .andExpect(jsonPath("$.maxFailureCount").value(5));
  }

  @Test
  @DisplayName("로그인 실패 횟수가 5회에 도달하면 잠금 응답을 반환한다")
  void returnsLockedResponseAfterFiveFailures() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@test.com", "wrong-password");

    when(loginFacade.login(any())).thenThrow(new LoginLockedException());

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"))
        .andExpect(jsonPath("$.failureCount").value(5))
        .andExpect(jsonPath("$.maxFailureCount").value(5))
        .andExpect(jsonPath("$.message").value("로그인 시도 횟수를 초과했습니다. 비밀번호를 변경해주세요."));
  }

  @Test
  @DisplayName("로그아웃하면 현재 기기의 로그인 세션을 종료한다")
  void logsOutCurrentSession() throws Exception {
    // Given
    String memberId = "member-1";
    String refreshToken = "refresh-token";

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(memberId, null);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/logout")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "refreshToken": "refresh-token"
                                    }
                                    """));

    // Then
    response.andExpect(status().isNoContent());

    verify(logoutService).logout(memberId, refreshToken);
  }

  @Test
  @DisplayName("로그인 이메일이 비어 있으면 400 응답을 반환한다")
  void returnsBadRequestWhenLoginEmailIsBlank() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("", "password");

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("이메일은 필수입니다."));

    verify(loginFacade, never()).login(any());
  }

  @Test
  @DisplayName("로그아웃 Refresh Token이 비어 있으면 400 응답을 반환한다")
  void returnsBadRequestWhenRefreshTokenIsBlank() throws Exception {
    // Given
    String memberId = "member-1";

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(memberId, null);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/logout")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "refreshToken": ""
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("Refresh Token은 필수입니다."));

    verify(logoutService, never()).logout(any(), any());
  }

  @Test
  @DisplayName("이미 사용 중인 이메일이면 available false를 반환한다")
  void returnsAvailableFalseWhenEmailAlreadyExists() throws Exception {
    // Given
    String email = "exists@test.com";

    when(signupService.isEmailAvailable(email)).thenReturn(false);

    // When
    ResultActions response =
        mockMvc.perform(get("/api/v1/auth/emails/check").param("email", email));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.data.available").value(false));

    verify(signupService).isEmailAvailable(email);
  }

  @Test
  @DisplayName("이메일 파라미터가 없으면 400 응답을 반환한다")
  void returnsBadRequestWhenEmailIsMissing() throws Exception {
    // When
    ResultActions response = mockMvc.perform(get("/api/v1/auth/emails/check"));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("이메일은 필수입니다."));

    verify(signupService, never()).isEmailAvailable(any());
  }

  @Test
  @DisplayName("회원가입에 성공하면 생성된 회원 정보를 201 응답으로 반환한다")
  void returnsCreatedMemberAfterSignup() throws Exception {
    // Given
    SignupRequest request =
        new SignupRequest(
            "test@test.com",
            "verification-token",
            "password123!",
            "테스트",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true,
                "PREFERENCE", false,
                "PROFILE_IMAGE", false));

    Member member = Member.createLocal("test@test.com", "encoded-password", "테스트");

    when(signupFacade.signup(any())).thenReturn(member);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.memberId").value(member.getId()))
        .andExpect(jsonPath("$.data.email").value("test@test.com"))
        .andExpect(jsonPath("$.data.nickname").value("테스트"))
        .andExpect(jsonPath("$.data.coupleConnected").value(false))
        .andExpect(jsonPath("$.data.createdAt").exists());

    verify(signupFacade).signup(any());
  }

  @Test
  @DisplayName("회원가입 이메일 인증 토큰이 비어 있으면 400 응답을 반환한다")
  void returnsBadRequestWhenSignupVerificationTokenIsBlank() throws Exception {
    // Given
    SignupRequest request =
        new SignupRequest(
            "test@test.com",
            "",
            "password123!",
            "테스트",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true,
                "PREFERENCE", false,
                "PROFILE_IMAGE", false));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("이메일 인증 토큰은 필수입니다."));

    verify(signupFacade, never()).signup(any());
  }

  @Test
  @DisplayName("이메일 인증번호 발급에 성공하면 유효 시간과 재발급 대기 시간을 반환한다")
  void issuesEmailVerificationCode() throws Exception {
    // Given
    EmailVerificationRequest request =
        new EmailVerificationRequest("test@test.com", EmailVerificationPurpose.SIGNUP);

    when(emailVerificationService.getCodeExpirationSeconds()).thenReturn(1800L);
    when(emailVerificationService.getResendCooldownSeconds()).thenReturn(60L);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.email").value("test@test.com"))
        .andExpect(jsonPath("$.data.purpose").value("SIGNUP"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(jsonPath("$.data.resendAvailableIn").value(60));

    verify(emailVerificationService).issueCode("test@test.com", EmailVerificationPurpose.SIGNUP);
  }

  @Test
  @DisplayName("올바른 이메일 인증번호를 확인하면 인증 완료 토큰을 반환한다")
  void confirmsEmailVerificationCode() throws Exception {
    // Given
    EmailVerificationConfirmRequest request =
        new EmailVerificationConfirmRequest(
            "test@test.com", "123456", EmailVerificationPurpose.SIGNUP);

    EmailVerificationConfirmResult result =
        EmailVerificationConfirmResult.verified("verification-token");

    when(emailVerificationService.confirmCode(
            "test@test.com", EmailVerificationPurpose.SIGNUP, "123456"))
        .thenReturn(result);

    when(emailVerificationService.getVerificationTokenExpirationSeconds()).thenReturn(1800L);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.verificationToken").value("verification-token"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800));

    verify(emailVerificationService)
        .confirmCode("test@test.com", EmailVerificationPurpose.SIGNUP, "123456");
  }

  @Test
  @DisplayName("유효한 Refresh Token이면 새 Access Token을 반환한다")
  void refreshesAccessTokenWithValidRefreshToken() throws Exception {
    // Given
    String refreshToken = "refresh-token";

    TokenRefreshResult result = new TokenRefreshResult("new-access-token", refreshToken, 1800L);

    when(tokenRefreshService.refresh(refreshToken)).thenReturn(result);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "refreshToken": "refresh-token"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
        .andExpect(jsonPath("$.data.refreshToken").value(refreshToken))
        .andExpect(jsonPath("$.data.expiresIn").value(1800));

    verify(tokenRefreshService).refresh(refreshToken);
  }

  @Test
  @DisplayName("토큰 갱신 Refresh Token이 비어 있으면 400 응답을 반환한다")
  void returnsBadRequestWhenRefreshTokenForRefreshIsBlank() throws Exception {
    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "refreshToken": ""
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("Refresh Token은 필수입니다."));

    verify(tokenRefreshService, never()).refresh(any());
  }

  @Test
  @DisplayName("비밀번호가 72바이트를 초과하면 회원가입을 거부한다")
  void rejectsSignupPasswordExceeding72Bytes() throws Exception {
    // Given
    SignupRequest request =
        new SignupRequest(
            "test@test.com",
            "verification-token",
            "가".repeat(25),
            "테스트",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요."));

    verify(signupFacade, never()).signup(any());
  }

  @Test
  @DisplayName("로그인 비밀번호가 72바이트를 초과하면 요청을 거부한다")
  void rejectsLoginPasswordExceeding72Bytes() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@test.com", "가".repeat(25));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요."));

    verify(loginFacade, never()).login(any());
  }

  @Test
  @DisplayName("인증번호가 6자리가 아니면 요청을 거부한다")
  void rejectsInvalidVerificationCodeLength() throws Exception {
    // Given
    EmailVerificationConfirmRequest request =
        new EmailVerificationConfirmRequest(
            "test@test.com", "12345", EmailVerificationPurpose.SIGNUP);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("인증번호는 숫자 6자리로 입력해 주세요."));

    // 형식 오류는 인증 실패 횟수에 포함하지 않음
    verify(emailVerificationService, never()).confirmCode(any(), any(), any());
  }

  @Test
  @DisplayName("이메일이 100자를 초과하면 회원가입을 거부한다")
  void rejectsSignupEmailExceeding100Characters() throws Exception {
    SignupRequest request =
        new SignupRequest(
            "a".repeat(101) + "@test.com",
            "verification-token",
            "password123!",
            "테스트",
            Map.of("TOS", true));

    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("이메일은 100자 이하로 입력해 주세요."));

    verify(signupFacade, never()).signup(any());
  }

  @Test
  @DisplayName("이메일이 100자를 초과하면 인증메일을 발송하지 않는다")
  void rejectsVerificationEmailExceeding100Characters() throws Exception {
    EmailVerificationRequest request =
        new EmailVerificationRequest(
            "a".repeat(101) + "@test.com", EmailVerificationPurpose.SIGNUP);

    mockMvc
        .perform(
            post("/api/v1/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(emailVerificationService, never()).issueCode(any(), any());
  }

  @Test
  @DisplayName("신규 Google 사용자는 로그인 토큰 대신 signupToken을 반환한다")
  void returnsSignupTokenForNewGoogleMember() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/google/callback";

    // 신규 회원이므로 DB 회원 정보나 Access Token은 아직 없음
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
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.isNewMember").value(true))
        .andExpect(jsonPath("$.data.signupToken").value("signup-token"))

        // 신규 회원에게는 Today-it 로그인 토큰을 발급하지 않음
        .andExpect(jsonPath("$.data.accessToken").doesNotExist())
        .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
        .andExpect(jsonPath("$.data.member").doesNotExist());

    verify(googleLoginService).login("google-code", redirectUri);
  }

  @Test
  @DisplayName("기존 Google 회원은 Access Token과 Refresh Token을 반환한다")
  void returnsLoginTokensForExistingGoogleMember() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/google/callback";

    MemberLoginResult member = new MemberLoginResult("member-1", "테스트", false, List.of("USER"));

    LoginResult loginResult = new LoginResult(member, "access-token", "refresh-token", 1800L);

    // 이미 Google 계정으로 가입된 회원의 로그인 결과
    when(googleLoginService.login("google-code", redirectUri))
        .thenReturn(GoogleLoginResult.existingMember(loginResult));

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
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.isNewMember").value(false))
        .andExpect(jsonPath("$.data.accessToken").value("access-token"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(jsonPath("$.data.member.memberId").value("member-1"))
        .andExpect(jsonPath("$.data.member.nickname").value("테스트"))
        .andExpect(jsonPath("$.data.member.roles[0]").value("USER"))

        // 기존 회원에게는 별도의 가입 토큰을 발급하지 않음
        .andExpect(jsonPath("$.data.signupToken").doesNotExist());

    verify(googleLoginService).login("google-code", redirectUri);
  }

  @Test
  @DisplayName("Google 인가 코드가 비어 있으면 400을 반환한다")
  void rejectsMissingGoogleAuthorizationCode() throws Exception {
    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "",
                                      "redirectUri": "http://localhost:3000/oauth/google/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    // 입력값이 유효하지 않으면 Google 인증 서비스 자체를 호출하지 않음
    verify(googleLoginService, never()).login(any(), any());
  }

  @Test
  @DisplayName("GOOGLE 회원가입에 성공하면 회원 정보와 201 응답을 반환한다")
  void returnsCreatedGoogleMemberAfterSignup() throws Exception {
    // Given
    SignupRequest request =
        new SignupRequest(
            MemberProvider.GOOGLE,
            null,
            null,
            null,
            "google-signup-token",
            "구글회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    Member member = Member.createGoogle("google-user-123", "구글회원");

    when(signupFacade.signup(any())).thenReturn(member);

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

    // Then
    response
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.memberId").value(member.getId()))
        .andExpect(jsonPath("$.data.email").doesNotExist())
        .andExpect(jsonPath("$.data.nickname").value("구글회원"));

    // Controller가 GOOGLE 가입 정보를 Facade에 전달했는지 확인
    verify(signupFacade)
        .signup(
            org.mockito.ArgumentMatchers.argThat(
                command ->
                    command.provider() == MemberProvider.GOOGLE
                        && "google-signup-token".equals(command.signupToken())));
  }

  @Test
  @DisplayName("신규 Kakao 사용자는 로그인 토큰 대신 signupToken을 반환한다")
  void returnsSignupTokenForNewKakaoMember() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/kakao/callback";

    when(kakaoLoginService.login("kakao-code", redirectUri))
        .thenReturn(KakaoLoginResult.newMember("signup-token"));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "kakao-code",
                                      "redirectUri": "http://localhost:3000/oauth/kakao/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.isNewMember").value(true))
        .andExpect(jsonPath("$.data.signupToken").value("signup-token"))
        .andExpect(jsonPath("$.data.accessToken").doesNotExist())
        .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
        .andExpect(jsonPath("$.data.member").doesNotExist());

    verify(kakaoLoginService).login("kakao-code", redirectUri);
  }

  @Test
  @DisplayName("기존 Kakao 회원에게 로그인 토큰과 회원 정보를 반환한다")
  void returnsTokensForExistingKakaoMember() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/kakao/callback";

    MemberLoginResult member = new MemberLoginResult("member-1", "카카오회원", false, List.of("USER"));

    LoginResult loginResult = new LoginResult(member, "access-token", "refresh-token", 1800L);

    when(kakaoLoginService.login("kakao-code", redirectUri))
        .thenReturn(KakaoLoginResult.existingMember(loginResult));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "kakao-code",
                                      "redirectUri": "http://localhost:3000/oauth/kakao/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.isNewMember").value(false))
        .andExpect(jsonPath("$.data.signupToken").doesNotExist())
        .andExpect(jsonPath("$.data.accessToken").value("access-token"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
        .andExpect(jsonPath("$.data.expiresIn").value(1800))
        .andExpect(jsonPath("$.data.member.memberId").value("member-1"))
        .andExpect(jsonPath("$.data.member.nickname").value("카카오회원"))
        .andExpect(jsonPath("$.data.member.roles[0]").value("USER"));

    verify(kakaoLoginService).login("kakao-code", redirectUri);
  }

  @Test
  @DisplayName("Kakao 인가 코드가 없으면 400 오류를 반환한다")
  void rejectsMissingKakaoAuthorizationCode() throws Exception {
    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "",
                                      "redirectUri": "http://localhost:3000/oauth/kakao/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("Kakao 인가 코드는 필수입니다."));

    verify(kakaoLoginService, never()).login(any(), any());
  }

  @Test
  @DisplayName("Kakao 인증 실패 시 커스텀 에러를 반환한다")
  void returnsKakaoAuthenticationError() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/kakao/callback";

    when(kakaoLoginService.login("invalid-code", redirectUri))
        .thenThrow(new BusinessException(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "invalid-code",
                                      "redirectUri": "http://localhost:3000/oauth/kakao/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("KAKAO_AUTHENTICATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Kakao 인증에 실패했습니다."));
  }

  @Test
  @DisplayName("Kakao 인증 서버 장애 시 500 커스텀 에러를 반환한다")
  void returnsKakaoAuthServerError() throws Exception {
    // Given
    String redirectUri = "http://localhost:3000/oauth/kakao/callback";

    when(kakaoLoginService.login("kakao-code", redirectUri))
        .thenThrow(new BusinessException(MemberErrorCode.KAKAO_AUTH_SERVER_ERROR));

    // When
    ResultActions response =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "authorizationCode": "kakao-code",
                                      "redirectUri": "http://localhost:3000/oauth/kakao/callback"
                                    }
                                    """));

    // Then
    response
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("KAKAO_AUTH_SERVER_ERROR"));
  }
}
