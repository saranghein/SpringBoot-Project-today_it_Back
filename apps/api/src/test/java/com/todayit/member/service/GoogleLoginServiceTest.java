package com.todayit.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.GoogleIdentity;
import com.todayit.member.client.external.GoogleOAuthClient;
import com.todayit.member.client.external.GoogleOAuthException;
import com.todayit.member.client.external.GoogleOAuthException.Reason;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.service.model.GoogleLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleLoginServiceTest {

  @Mock private GoogleOAuthClient googleOAuthClient;
  @Mock private MemberRepository memberRepository;
  @Mock private GoogleSignupTokenService googleSignupTokenService;
  @Mock private LoginFacade loginFacade;
  @Mock private Member member;

  private GoogleLoginService service;

  @BeforeEach
  void setUp() {
    service =
        new GoogleLoginService(
            googleOAuthClient, memberRepository, googleSignupTokenService, loginFacade);
  }

  @Test
  @DisplayName("기존 Google 회원은 Today-it 로그인 토큰을 발급받는다")
  void logsInExistingGoogleMember() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-123", "test@gmail.com", true);

    when(googleOAuthClient.authenticate("code", "redirect-uri")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.GOOGLE, "google-123"))
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
    GoogleLoginResult result = service.login("code", "redirect-uri");

    // Then
    assertThat(result.isNewMember()).isFalse();
    assertThat(result.signupToken()).isNull();
    assertThat(result.loginResult()).isEqualTo(loginResult);

    ArgumentCaptor<MemberLoginResult> memberCaptor =
        ArgumentCaptor.forClass(MemberLoginResult.class);

    verify(loginFacade).issueTokens(memberCaptor.capture());

    assertThat(memberCaptor.getValue().memberId()).isEqualTo("member-1");
    assertThat(memberCaptor.getValue().roles()).containsExactly("USER");

    verify(googleSignupTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("신규 Google 사용자는 회원을 생성하지 않고 임시 가입 토큰을 발급받는다")
  void issuesSignupTokenForNewGoogleUser() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-new", null, false);

    when(googleOAuthClient.authenticate("code", "redirect-uri")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.GOOGLE, "google-new"))
        .thenReturn(Optional.empty());

    when(googleSignupTokenService.issue(identity)).thenReturn("signup-token");

    // When
    GoogleLoginResult result = service.login("code", "redirect-uri");

    // Then
    assertThat(result.isNewMember()).isTrue();
    assertThat(result.signupToken()).isEqualTo("signup-token");
    assertThat(result.loginResult()).isNull();

    verify(googleSignupTokenService).issue(identity);
    verify(loginFacade, never()).issueTokens(any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("비활성 Google 회원은 로그인할 수 없다")
  void rejectsInactiveGoogleMember() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-123", "test@gmail.com", true);

    when(googleOAuthClient.authenticate("code", "redirect-uri")).thenReturn(identity);

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.GOOGLE, "google-123"))
        .thenReturn(Optional.of(member));

    when(member.isActive()).thenReturn(false);

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.GOOGLE_AUTHENTICATION_FAILED);

    verify(loginFacade, never()).issueTokens(any());
    verify(googleSignupTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("Google 인가 코드가 잘못됐으면 인증 실패 오류를 반환한다")
  void rejectsInvalidGoogleAuthorizationCode() {
    // Given
    when(googleOAuthClient.authenticate("bad-code", "redirect-uri"))
        .thenThrow(new GoogleOAuthException(Reason.INVALID_AUTHORIZATION_CODE));

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("bad-code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.GOOGLE_AUTHENTICATION_FAILED);
  }

  @Test
  @DisplayName("Google 인증 서버에 연결할 수 없으면 서버 오류로 변환한다")
  void handlesGoogleProviderUnavailable() {
    // Given
    when(googleOAuthClient.authenticate("code", "redirect-uri"))
        .thenThrow(new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE));

    // When
    BusinessException exception =
        assertThrows(BusinessException.class, () -> service.login("code", "redirect-uri"));

    // Then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.GOOGLE_AUTH_SERVER_ERROR);
  }
}
