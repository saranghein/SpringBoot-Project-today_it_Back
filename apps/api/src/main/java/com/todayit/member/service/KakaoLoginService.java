package com.todayit.member.service;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.KakaoIdentity;
import com.todayit.member.client.external.KakaoOAuthClient;
import com.todayit.member.client.external.KakaoOAuthException;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.service.model.KakaoLoginResult;
import com.todayit.member.service.model.LoginResult;
import com.todayit.member.service.model.MemberLoginResult;
import java.util.List;
import org.springframework.stereotype.Service;

/** Kakao 인증 결과를 Today-it 로그인 또는 회원가입 흐름으로 연결합니다. */
@Service
public class KakaoLoginService {
  private final KakaoOAuthClient kakaoOAuthClient;
  private final MemberRepository memberRepository;
  private final KakaoSignupTokenService kakaoSignupTokenService;
  private final LoginFacade loginFacade;

  /**
   * Kakao 로그인 처리에 필요한 객체를 주입받습니다.
   *
   * @param kakaoOAuthClient Kakao OAuth 연동 클라이언트
   * @param memberRepository 회원 조회 Repository
   * @param kakaoSignupTokenService 신규 회원 임시 토큰 서비스
   * @param loginFacade Today-it 로그인 토큰 발급 서비스
   */
  public KakaoLoginService(
      KakaoOAuthClient kakaoOAuthClient,
      MemberRepository memberRepository,
      KakaoSignupTokenService kakaoSignupTokenService,
      LoginFacade loginFacade) {
    this.kakaoOAuthClient = kakaoOAuthClient;
    this.memberRepository = memberRepository;
    this.kakaoSignupTokenService = kakaoSignupTokenService;
    this.loginFacade = loginFacade;
  }

  /**
   * Kakao 인증 결과를 확인하고 기존 회원 로그인 또는 신규 회원가입을 처리합니다.
   *
   * <p>기존 회원은 Today-it 로그인 토큰을 발급받으며, 신규 회원은 Redis에 인증 정보를 저장하고 임시 회원가입 토큰을 발급받습니다. 이메일이 없더라도 Kakao
   * 고유 ID로 회원을 식별합니다.
   *
   * @param authorizationCode Kakao 인가 코드
   * @param redirectUri Kakao 인증 요청에 사용한 Redirect URI
   * @return Kakao 로그인 또는 신규 회원가입 결과
   * @throws BusinessException Kakao 인증 실패 또는 비활성 회원인 경우
   * @throws IllegalStateException 회원 권한 정보가 없는 경우
   */
  public KakaoLoginResult login(String authorizationCode, String redirectUri) {
    KakaoIdentity identity;

    // 외부 인증 과정에서 발생한 오류를 Today-it 업무 오류로 변환
    // 외부 HTTP 호출은 DB 트랜잭션 밖에서 수행
    try {
      // 인가 코드로 Kakao Access Token 발급
      String accessToken = kakaoOAuthClient.exchangeCode(authorizationCode, redirectUri);

      // Kakao Access Token으로 사용자 정보 조회
      identity = kakaoOAuthClient.getUserInfo(accessToken);
    } catch (KakaoOAuthException exception) {
      throw convertKakaoError(exception);
    }

    // Kakao 사용자 고유 ID는 회원 식별에 반드시 필요
    if (identity == null
        || identity.providerUserId() == null
        || identity.providerUserId().isBlank()
        || identity.providerUserId().length() > 255) {
      throw new BusinessException(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);
    }

    // 이메일이 아닌 Kakao 고유 ID를 기준으로 기존 회원 조회
    // 이메일이 같더라도 LOCAL 또는 GOOGLE 회원과 자동 연결하지 않음
    Member member =
        memberRepository
            .findByProviderAndProviderUserId(MemberProvider.KAKAO, identity.providerUserId())
            .orElse(null);

    // 신규 회원이라면 임시 회원가입 토큰 발급
    if (member == null) {
      // 이메일이 없는 Kakao 계정도 토큰을 발급할 수 있음
      String signupToken = kakaoSignupTokenService.issue(identity);

      return KakaoLoginResult.newMember(signupToken);
    }

    // 탈퇴했거나 비활성화된 기존 회원은 로그인 차단
    if (!member.isActive()) {
      throw new BusinessException(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);
    }

    // 기존 Kakao 회원의 권한 조회
    List<String> roles = memberRepository.findRoleNamesByMemberId(member.getId());

    // 권한 정보가 없는 회원에게는 로그인 토큰을 발급하지 않음
    if (roles.isEmpty()) {
      throw new IllegalStateException("회원 권한 정보가 없습니다.");
    }

    // Kakao 인증이 완료된 회원 정보를 로그인 결과 모델로 변환
    MemberLoginResult authenticatedMember =
        new MemberLoginResult(member.getId(), member.getNickname(), false, roles);

    // 기존 LOCAL 및 GOOGLE 로그인과 동일하게 토큰 발급
    LoginResult loginResult = loginFacade.issueTokens(authenticatedMember);

    return KakaoLoginResult.existingMember(loginResult);
  }

  /**
   * Kakao OAuth 연동 오류를 Today-it 업무 오류로 변환합니다.
   *
   * @param exception Kakao OAuth 연동 오류
   * @return Today-it 비즈니스 예외
   */
  private BusinessException convertKakaoError(KakaoOAuthException exception) {
    KakaoOAuthException.Reason reason = exception.getReason();

    // Kakao 인증 서버 장애 또는 외부 API 요청 거부
    if (reason == KakaoOAuthException.Reason.PROVIDER_UNAVAILABLE
        || reason == KakaoOAuthException.Reason.PROVIDER_REQUEST_REJECTED) {
      return new BusinessException(MemberErrorCode.KAKAO_AUTH_SERVER_ERROR);
    }

    // 인가 코드, Access Token 또는 사용자 정보가 유효하지 않은 경우
    if (reason == KakaoOAuthException.Reason.INVALID_AUTHORIZATION_CODE
        || reason == KakaoOAuthException.Reason.INVALID_ACCESS_TOKEN
        || reason == KakaoOAuthException.Reason.INVALID_USER_INFO) {
      return new BusinessException(MemberErrorCode.KAKAO_AUTHENTICATION_FAILED);
    }

    // 정의되지 않은 OAuth 오류 유형
    throw new IllegalStateException("지원하지 않는 Kakao OAuth 오류 유형입니다.");
  }
}
