package com.todayit.member.service;

import com.todayit.common.exception.InvalidRequestException;
import com.todayit.member.client.external.GoogleIdentity;
import com.todayit.member.client.external.KakaoIdentity;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.service.command.SignupCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** LOCAL·GOOGLE·KAKAO 회원가입 과정을 연결합니다. */
@Service
public class SignupFacade {
  private static final Logger LOG = LoggerFactory.getLogger(SignupFacade.class);

  private final SignupService signupService;
  private final GoogleSignupTokenService googleSignupTokenService;
  private final KakaoSignupTokenService kakaoSignupTokenService;

  /**
   * 회원가입 처리에 필요한 서비스를 주입받습니다.
   *
   * @param signupService 회원 생성 및 DB 저장 서비스
   * @param googleSignupTokenService Google 임시 가입 토큰 서비스
   * @param kakaoSignupTokenService Kakao 임시 가입 토큰 서비스
   */
  public SignupFacade(
      SignupService signupService,
      GoogleSignupTokenService googleSignupTokenService,
      KakaoSignupTokenService kakaoSignupTokenService) {
    this.signupService = signupService;
    this.googleSignupTokenService = googleSignupTokenService;
    this.kakaoSignupTokenService = kakaoSignupTokenService;
  }

  /**
   * 회원가입 방식에 따라 회원을 생성합니다.
   *
   * <p>Facade에는 DB 트랜잭션을 적용하지 않습니다. 실제 회원 저장이 완료된 이후 소셜 가입 토큰을 삭제합니다.
   *
   * @param command 회원가입 요청 정보
   * @return 생성된 회원
   * @throws InvalidRequestException 지원하지 않는 회원가입 방식인 경우
   */
  public Member signup(SignupCommand command) {
    // LOCAL 회원가입
    if (command.provider() == MemberProvider.LOCAL) {
      return signupService.createLocalMember(command);
    }

    // GOOGLE 회원가입
    if (command.provider() == MemberProvider.GOOGLE) {
      return signupGoogle(command);
    }

    // KAKAO 회원가입
    if (command.provider() == MemberProvider.KAKAO) {
      return signupKakao(command);
    }

    throw new InvalidRequestException("지원하지 않는 회원가입 방식입니다.");
  }

  /**
   * Google 임시 가입 토큰을 검증한 뒤 실제 회원을 생성합니다.
   *
   * @param command GOOGLE 회원가입 정보
   * @return 생성된 Google 회원
   */
  private Member signupGoogle(SignupCommand command) {
    String signupToken = command.signupToken();

    // Redis에서 Google 인증 정보 조회
    // 여기서는 토큰을 삭제하지 않음
    GoogleIdentity identity = googleSignupTokenService.getVerifiedIdentity(signupToken);

    // GOOGLE 회원 + 기본 권한 + 약관 동의를 DB에 저장
    // @Transactional 메서드가 반환되면 DB 커밋까지 완료된 상태
    Member member = signupService.createGoogleMember(command, identity);

    // 회원가입에 성공했으므로 사용한 임시 토큰 삭제
    try {
      googleSignupTokenService.invalidate(signupToken);
    } catch (DataAccessException exception) {
      // DB 가입은 이미 성공했으므로 Redis 정리 실패 때문에
      // 클라이언트에 회원가입 실패를 반환하지 않음
      // 남은 토큰이 재사용되더라도 DB UNIQUE 제약으로 중복 가입 방어
      LOG.error("Google 회원가입 완료 후 임시 토큰 삭제에 실패했습니다. memberId={}", member.getId(), exception);
    }

    return member;
  }

  /**
   * Kakao 임시 가입 토큰을 검증한 뒤 회원을 생성합니다.
   *
   * @param command KAKAO 회원가입 정보
   * @return 생성된 Kakao 회원
   */
  private Member signupKakao(SignupCommand command) {
    String signupToken = command.signupToken();

    // Redis에서 인증된 Kakao 사용자 정보 조회
    KakaoIdentity identity = kakaoSignupTokenService.getVerifiedIdentity(signupToken);

    // KAKAO 회원, USER 권한, 약관 동의를 DB에 저장
    // @Transactional 메서드 반환 후 DB 커밋 완료
    Member member = signupService.createKakaoMember(command, identity);

    // 회원가입이 성공한 경우에만 임시 토큰 삭제
    try {
      kakaoSignupTokenService.invalidate(signupToken);
    } catch (DataAccessException exception) {
      // 회원가입은 이미 완료되었으므로 Redis 정리 실패로
      // 사용자에게 회원가입 실패 응답을 반환하지 않음
      LOG.error("Kakao 회원가입 완료 후 임시 토큰 삭제에 실패했습니다. memberId={}", member.getId(), exception);
    }

    return member;
  }
}
