package com.todayit.member.service;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.GoogleIdentity;
import com.todayit.member.client.external.GoogleOAuthClient;
import com.todayit.member.client.external.GoogleOAuthException;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.service.model.GoogleLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import java.util.List;
import org.springframework.stereotype.Service;

/** Google 인증 결과를 Today-it 회원 로그인 또는 회원가입 흐름으로 연결합니다. */
@Service
public class GoogleLoginService {
  private final GoogleOAuthClient googleOAuthClient;
  private final MemberRepository memberRepository;
  private final GoogleSignupTokenService googleSignupTokenService;
  private final LoginFacade loginFacade;

  /**
   * Google 로그인 처리에 필요한 객체를 주입받습니다.
   *
   * @param googleOAuthClient Google 인증 클라이언트
   * @param memberRepository 회원 Repository
   * @param googleSignupTokenService 신규 회원 임시 토큰 발급 서비스
   * @param loginFacade 로그인 토큰 발급 서비스
   */
  public GoogleLoginService(
      GoogleOAuthClient googleOAuthClient,
      MemberRepository memberRepository,
      GoogleSignupTokenService googleSignupTokenService,
      LoginFacade loginFacade) {
    this.googleOAuthClient = googleOAuthClient;
    this.memberRepository = memberRepository;
    this.googleSignupTokenService = googleSignupTokenService;
    this.loginFacade = loginFacade;
  }

  /**
   * Google 인증 결과를 확인하고 기존 회원 로그인 또는 신규 회원가입을 처리합니다.
   *
   * <p>기존 회원은 Today-it 로그인 토큰을 발급하고, 신규 회원은 Redis에 인증 정보를 저장한 뒤 임시 가입 토큰을 발급합니다.
   *
   * @param authorizationCode Google 인가 코드
   * @param redirectUri Google 인증 요청에 사용한 Redirect URI
   * @return Google 로그인 또는 신규 회원가입 결과
   * @throws BusinessException Google 인증 실패, 비활성 회원인 경우
   * @throws IllegalStateException 기존 회원에게 유효한 권한이 없는 경우
   */
  public GoogleLoginResult login(String authorizationCode, String redirectUri) {
    GoogleIdentity identity;

    // Google 인가 코드를 ID Token으로 교환하고 사용자 정보 검증
    // 외부 API 호출은 DB 트랜잭션 밖에서 수행
    try {
      identity = googleOAuthClient.authenticate(authorizationCode, redirectUri);
    } catch (GoogleOAuthException exception) {
      // Google 외부 연동 오류를 Today-it 공통 비즈니스 오류로 변환
      throw convertGoogleError(exception);
    }

    // Google 인증 결과에서 사용자 고유 ID 확인
    if (identity == null
        || identity.providerUserId() == null
        || identity.providerUserId().isBlank()
        || identity.providerUserId().length() > 255) {
      throw new BusinessException(MemberErrorCode.GOOGLE_AUTHENTICATION_FAILED);
    }

    // Google 계정 고유 ID(sub)를 기준으로 기존 회원 조회
    // 이메일이 같더라도 LOCAL 회원과 자동으로 연결하지 않음
    Member member =
        memberRepository
            .findByProviderAndProviderUserId(MemberProvider.GOOGLE, identity.providerUserId())
            .orElse(null);

    // 가입된 Google 회원이 없는 경우 신규 회원으로 처리
    if (member == null) {
      // Google 계정 정보를 Redis에 30분간 임시 저장하고 signupToken 발급
      String signupToken = googleSignupTokenService.issue(identity);

      return GoogleLoginResult.newMember(signupToken);
    }

    // 탈퇴 등으로 비활성화된 기존 회원은 로그인 차단
    if (!member.isActive()) {
      throw new BusinessException(MemberErrorCode.GOOGLE_AUTHENTICATION_FAILED);
    }

    // 기존 Google 회원에게 부여된 권한 목록 조회
    List<String> roles = memberRepository.findRoleNamesByMemberId(member.getId());

    // 유효한 권한이 없다면 로그인 토큰을 발급하지 않음
    if (roles.isEmpty()) {
      throw new IllegalStateException("회원 권한 정보가 없습니다.");
    }

    // Google 인증 및 회원 상태 검증이 완료된 정보를 로그인 결과 모델로 변환
    MemberLoginResult authenticatedMember =
        new MemberLoginResult(member.getId(), member.getNickname(), false, roles);

    // LOCAL 로그인과 동일한 방식으로 Access Token 및 Refresh Token 발급
    LoginResult loginResult = loginFacade.issueTokens(authenticatedMember);

    // 기존 회원은 별도 회원가입 없이 Today-it에 로그인
    return GoogleLoginResult.existingMember(loginResult);
  }

  /**
   * Google 연동 오류를 Today-it API에서 사용하는 오류로 변환합니다.
   *
   * @param exception Google OAuth 연동 오류
   * @return Today-it 비즈니스 예외
   */
  private BusinessException convertGoogleError(GoogleOAuthException exception) {
    GoogleOAuthException.Reason reason = exception.getReason();

    // Google 인증 서버 장애인 경우 서버 오류로 변환
    if (reason == GoogleOAuthException.Reason.PROVIDER_UNAVAILABLE) {
      return new BusinessException(MemberErrorCode.GOOGLE_AUTH_SERVER_ERROR);
    }

    // 인가 코드 또는 ID Token이 유효하지 않은 경우 인증 실패 처리
    if (reason == GoogleOAuthException.Reason.INVALID_AUTHORIZATION_CODE
        || reason == GoogleOAuthException.Reason.INVALID_ID_TOKEN) {
      return new BusinessException(MemberErrorCode.GOOGLE_AUTHENTICATION_FAILED);
    }

    // 추후 새로운 오류 유형이 추가되었지만 처리하지 않은 경우
    throw new IllegalStateException("지원하지 않는 Google OAuth 오류 유형입니다.");
  }
}
