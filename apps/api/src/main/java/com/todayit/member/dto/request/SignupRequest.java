package com.todayit.member.dto.request;

import com.todayit.common.exception.InvalidRequestException;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.service.command.SignupCommand;
import com.todayit.member.util.PasswordValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * LOCAL, GOOGLE 및 KAKAO 회원가입 요청입니다.
 *
 * @param provider 회원가입 방식. 생략하면 LOCAL
 * @param email LOCAL 회원 이메일
 * @param emailVerificationToken LOCAL 이메일 인증 완료 토큰
 * @param password LOCAL 회원 비밀번호
 * @param signupToken GOOGLE 또는 KAKAO 회원가입용 임시 토큰
 * @param nickname 회원 닉네임
 * @param agreements 약관 코드별 동의 여부
 */
@Schema(description = "LOCAL, GOOGLE 및 KAKAO 통합 회원가입 요청")
public record SignupRequest(
    @Schema(
            description = "회원가입 방식입니다. LOCAL 또는 GOOGLE 및 KAKAO을 지원하며 생략 시 LOCAL입니다.",
            example = "LOCAL",
            defaultValue = "LOCAL")
        MemberProvider provider,
    @Schema(description = "LOCAL 회원가입 시 입력할 이메일입니다.", example = "user@example.com", maxLength = 100)
        String email,
    @Schema(description = "LOCAL 회원가입 시 사용할 이메일 인증 완료 토큰입니다.") String emailVerificationToken,
    @Schema(
            description =
                "LOCAL 회원가입 비밀번호입니다. 10~72자, UTF-8 72바이트 이하이며 영문·숫자·특수문자를 각각 1개 이상 포함해야 합니다.",
            format = "password",
            accessMode = Schema.AccessMode.WRITE_ONLY)
        String password,
    @Schema(description = "GOOGLE 또는 KAKAO 인증 완료 후 발급받은 회원가입용 임시 토큰입니다.") String signupToken,
    @Schema(
            description = "닉네임은 앞뒤 공백 제거 후 한글, 영문, 숫자 2~8자입니다.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname,
    @Schema(
            description = "약관 코드별 동의 여부입니다. 필수 약관은 TOS, ACCOUNT_PRIVACY입니다.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Boolean> agreements) {

  /** 가입 방식 기본값을 설정하고 닉네임을 정규화합니다. */
  public SignupRequest {
    // provider 생략 시 기존 LOCAL 회원가입으로 처리
    if (provider == null) {
      provider = MemberProvider.LOCAL;
    }

    // 회원가입 방식과 관계없이 닉네임 앞뒤 공백 제거
    if (nickname != null) {
      nickname = nickname.strip();
    }
  }

  /**
   * 기존 LOCAL 회원가입 코드와의 호환성을 유지합니다.
   *
   * @param email 회원 이메일
   * @param emailVerificationToken 이메일 인증 완료 토큰
   * @param password 회원 비밀번호
   * @param nickname 회원 닉네임
   * @param agreements 약관 동의 정보
   */
  public SignupRequest(
      String email,
      String emailVerificationToken,
      String password,
      String nickname,
      Map<String, Boolean> agreements) {
    this(MemberProvider.LOCAL, email, emailVerificationToken, password, null, nickname, agreements);
  }

  /**
   * 회원가입 요청을 서비스 전달 모델로 변환합니다.
   *
   * @return 회원가입 서비스에 전달할 정보
   */
  public SignupCommand toCommand() {
    return new SignupCommand(
        provider, email, emailVerificationToken, password, signupToken, nickname, agreements);
  }

  /**
   * 회원가입 방식에 따라 필수 입력값을 검증합니다.
   *
   * @throws InvalidRequestException 입력값이 유효하지 않은 경우
   */
  public void validate() {
    // LOCAL 회원가입
    if (provider == MemberProvider.LOCAL) {
      validateLocal();

      // GOOGLE 회원가입
    } else if (provider == MemberProvider.GOOGLE) {
      validateGoogle();

      // KAKAO 회원가입
    } else if (provider == MemberProvider.KAKAO) {
      validateKakao();

    } else {
      throw new InvalidRequestException("지원하지 않는 회원가입 방식입니다.");
    }

    // 모든 가입 방식에서 닉네임과 약관 동의는 필수
    validateNickname();

    if (agreements == null || agreements.isEmpty()) {
      throw new InvalidRequestException("약관 동의 정보는 필수입니다.");
    }
  }

  /** LOCAL 회원가입에 필요한 입력값을 확인합니다. */
  private void validateLocal() {
    if (email == null || email.isBlank()) {
      throw new InvalidRequestException("이메일은 필수입니다.");
    }

    if (email.length() > 100) {
      throw new InvalidRequestException("이메일은 100자 이하로 입력해 주세요.");
    }

    if (emailVerificationToken == null || emailVerificationToken.isBlank()) {
      throw new InvalidRequestException("이메일 인증 토큰은 필수입니다.");
    }

    // 공통 비밀번호 검증 수행
    // 길이, 바이트 수, 영문·숫자·특수문자 필수 여부 확인
    PasswordValidator.validate(password);

    // LOCAL 회원가입에서 Google 임시 가입 토큰 사용 금지
    if (signupToken != null) {
      throw new InvalidRequestException("LOCAL 회원가입에는 signupToken을 사용할 수 없습니다.");
    }
  }

  /** GOOGLE 회원가입에 필요한 입력값을 확인합니다. */
  private void validateGoogle() {

    // Google 인증 성공 시 발급받은 임시 가입 토큰 확인
    if (signupToken == null || signupToken.isBlank()) {
      throw new InvalidRequestException("Google 회원가입 토큰은 필수입니다.");
    }

    // Google 고유 ID는 서버에 저장된 가입 토큰 정보에서 가져옴
    // 프론트가 임의로 전달한 이메일이나 비밀번호는 받지 않음
    if (email != null || emailVerificationToken != null || password != null) {
      throw new InvalidRequestException("GOOGLE 회원가입에는 이메일 인증 정보나 비밀번호를 입력할 수 없습니다.");
    }
  }

  /** KAKAO 회원가입에 필요한 입력값을 검증합니다. */
  private void validateKakao() {
    // Kakao 인증 완료 후 발급받은 임시 가입 토큰 필수
    if (signupToken == null || signupToken.isBlank()) {
      throw new InvalidRequestException("Kakao 회원가입 토큰은 필수입니다.");
    }

    // Kakao 고유 ID는 서버에 저장된 가입 토큰 정보에서 가져옴
    // 클라이언트가 임의로 이메일이나 비밀번호를 전달하지 않도록 차단
    if (email != null || emailVerificationToken != null || password != null) {
      throw new InvalidRequestException("KAKAO 회원가입에는 이메일 인증 정보나 비밀번호를 입력할 수 없습니다.");
    }
  }

  /** 모든 가입 방식에서 사용하는 닉네임 입력값을 확인합니다. */
  private void validateNickname() {
    if (nickname == null || nickname.isBlank()) {
      throw new InvalidRequestException("닉네임은 필수입니다.");
    }

    if (nickname.length() < 2 || nickname.length() > 8) {
      throw new InvalidRequestException("닉네임은 2자 이상 8자 이하이어야 합니다.");
    }

    if (!nickname.matches("[가-힣A-Za-z0-9]+")) {
      throw new InvalidRequestException("닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.");
    }
  }
}
