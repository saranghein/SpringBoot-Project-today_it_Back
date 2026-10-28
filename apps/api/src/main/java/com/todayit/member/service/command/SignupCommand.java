package com.todayit.member.service.command;

import com.todayit.member.entity.MemberProvider;
import java.util.Map;

/**
 * 회원가입 서비스에 전달할 정보입니다.
 *
 * @param provider 회원가입 방식
 * @param email LOCAL 회원 이메일
 * @param emailVerificationToken LOCAL 이메일 인증 완료 토큰
 * @param password LOCAL 회원 비밀번호
 * @param signupToken GOOGLE 또는 KAKAO 회원가입용 임시 토큰
 * @param nickname 회원 닉네임
 * @param agreements 약관 코드별 동의 여부
 */
public record SignupCommand(
    MemberProvider provider,
    String email,
    String emailVerificationToken,
    String password,
    String signupToken,
    String nickname,
    Map<String, Boolean> agreements) {

  /**
   * 회원가입 서비스에 전달할 닉네임을 정규화합니다.
   *
   * <p>HTTP 요청을 거치지 않고 Command를 직접 생성하더라도 닉네임 앞뒤 공백을 제거합니다.
   */
  public SignupCommand {

    // 닉네임이 null이 아닐 때만 앞뒤 공백 제거
    if (nickname != null) {
      nickname = nickname.strip();
    }
  }

  /**
   * 기존 LOCAL 회원가입 서비스 테스트와의 호환성을 유지합니다.
   *
   * @param email 회원 이메일
   * @param emailVerificationToken 이메일 인증 완료 토큰
   * @param password 회원 비밀번호
   * @param nickname 회원 닉네임
   * @param agreements 약관 동의 정보
   */
  public SignupCommand(
      String email,
      String emailVerificationToken,
      String password,
      String nickname,
      Map<String, Boolean> agreements) {

    this(MemberProvider.LOCAL, email, emailVerificationToken, password, null, nickname, agreements);
  }
}
