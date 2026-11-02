package com.todayit.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.KakaoIdentity;
import com.todayit.member.client.external.KakaoOAuthClient;
import com.todayit.member.client.external.KakaoOAuthException;
import com.todayit.member.client.external.KakaoOAuthException.Reason;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.service.model.KakaoLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoLoginServiceTest {
  @Mock private KakaoOAuthClient kakaoOAuthClient;
  @Mock private MemberRepository memberRepository;
  @Mock private KakaoSignupTokenService kakaoSignupTokenService;
  @Mock private LoginFacade loginFacade;
  @Mock private Member member;

  private KakaoLoginService service;

  @BeforeEach
  void setUp() {
    service =
        new KakaoLoginService(
            kakaoOAuthClient, memberRepository, kakaoSignupTokenService, loginFacade);
  }

  @Test
  @DisplayName("이메일이 없는 기존 Kakao 회원도 로그인할 수 있다")
  void logsInExistingKakaoMemberWithoutEmail() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri")).thenReturn("kakao-access-token");

    when(kakaoOAuthClient.getUserInfo("kakao-access-token")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, "123456789"))
        .thenReturn(Optional.of(member));

    when(member.isActive()).thenReturn(true);
    when(member.getId()).thenReturn("member-1");
    when(member.getNickname()).thenReturn("테스트");

    when(memberRepository.findRoleNamesByMemberId("member-1")).thenReturn(List.of("USER"));

    LoginResult loginResult =
        new LoginResult(
            new MemberLoginResult("member-1", "테스트", false, List.of("USER")),
            "access-token",
            "refresh-token",
            1800L);

    when(loginFacade.issueTokens(any(MemberLoginResult.class))).thenReturn(loginResult);

    // When
    KakaoLoginResult result = service.login("code", "redirect-uri");

    // Then
    assertThat(result.isNewMember()).isFalse();
    assertThat(result.signupToken()).isNull();
    assertThat(result.loginResult()).isEqualTo(loginResult);

    ArgumentCaptor<MemberLoginResult> memberCaptor =
        ArgumentCaptor.forClass(MemberLoginResult.class);

    verify(loginFacade).issueTokens(memberCaptor.capture());

    assertThat(memberCaptor.getValue().memberId()).isEqualTo("member-1");

    assertThat(memberCaptor.getValue().roles()).containsExactly("USER");

    verify(kakaoSignupTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("이메일이 없는 신규 Kakao 사용자는 가입 토큰을 발급받는다")
  void issuesSignupTokenForNewKakaoUser() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri")).thenReturn("kakao-access-token");

    when(kakaoOAuthClient.getUserInfo("kakao-access-token")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, "123456789"))
        .thenReturn(Optional.empty());

    when(kakaoSignupTokenService.issue(identity)).thenReturn("signup-token");

    // When
    KakaoLoginResult result = service.login("code", "redirect-uri");

    // Then
    assertThat(result.isNewMember()).isTrue();
    assertThat(result.signupToken()).isEqualTo("signup-token");
    assertThat(result.loginResult()).isNull();

    verify(kakaoSignupTokenService).issue(identity);
    verify(loginFacade, never()).issueTokens(any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("비활성 Kakao 회원은 로그인할 수 없다")
  void rejectsInactiveKakaoMember() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri")).thenReturn("kakao-access-token");

    when(kakaoOAuthClient.getUserInfo("kakao-access-token")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, "123456789"))
        .thenReturn(Optional.of(member));

    when(member.isActive()).thenReturn(false);

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);

    verify(loginFacade, never()).issueTokens(any());
    verify(kakaoSignupTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("Kakao 사용자 ID가 없으면 로그인 처리를 중단한다")
  void rejectsMissingProviderUserId() {
    // Given
    KakaoIdentity identity = new KakaoIdentity(null, null, false);

    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri")).thenReturn("kakao-access-token");

    when(kakaoOAuthClient.getUserInfo("kakao-access-token")).thenReturn(identity);

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);

    verifyNoInteractions(memberRepository);
    verify(kakaoSignupTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("권한이 없는 Kakao 회원에게 로그인 토큰을 발급하지 않는다")
  void rejectsMemberWithoutRoles() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri")).thenReturn("kakao-access-token");

    when(kakaoOAuthClient.getUserInfo("kakao-access-token")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, "123456789"))
        .thenReturn(Optional.of(member));

    when(member.isActive()).thenReturn(true);
    when(member.getId()).thenReturn("member-1");

    when(memberRepository.findRoleNamesByMemberId("member-1")).thenReturn(List.of());

    // When
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getMessage()).isEqualTo("회원 권한 정보가 없습니다.");

    verify(loginFacade, never()).issueTokens(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = Reason.class,
      names = {"INVALID_AUTHORIZATION_CODE", "INVALID_ACCESS_TOKEN", "INVALID_USER_INFO"})
  @DisplayName("Kakao 인증 오류를 Today-it 인증 실패 오류로 변환한다")
  void convertsKakaoAuthenticationErrors(Reason reason) {
    // Given
    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri"))
        .thenThrow(new KakaoOAuthException(reason));

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);

    verifyNoInteractions(memberRepository);
  }

  @ParameterizedTest
  @EnumSource(
      value = Reason.class,
      names = {"PROVIDER_UNAVAILABLE", "PROVIDER_REQUEST_REJECTED"})
  @DisplayName("Kakao 외부 연동 오류를 Today-it 서버 오류로 변환한다")
  void convertsKakaoProviderErrors(Reason reason) {
    // Given
    when(kakaoOAuthClient.exchangeCode("code", "redirect-uri"))
        .thenThrow(new KakaoOAuthException(reason));

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.KAKAO_AUTH_SERVER_ERROR);

    verifyNoInteractions(memberRepository);
  }
}
