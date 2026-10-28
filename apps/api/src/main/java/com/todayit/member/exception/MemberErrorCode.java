package com.todayit.member.exception;

import com.todayit.common.exception.ErrorCode;
import com.todayit.common.exception.ErrorStatus;

/** 회원 기능에서 사용하는 오류 코드입니다. */
public enum MemberErrorCode implements ErrorCode {
  LOGIN_FAILED("LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.", ErrorStatus.UNAUTHORIZED),

  LOGIN_LOCKED("LOGIN_LOCKED", "로그인 시도 횟수를 초과했습니다. 비밀번호를 변경해주세요.", ErrorStatus.UNAUTHORIZED),

  EMAIL_VERIFICATION_CODE_EXPIRED(
      "EMAIL_VERIFICATION_CODE_EXPIRED", "인증번호가 만료되었거나 존재하지 않습니다.", ErrorStatus.BAD_REQUEST),

  EMAIL_VERIFICATION_CODE_INVALID(
      "EMAIL_VERIFICATION_CODE_INVALID", "인증번호가 올바르지 않습니다.", ErrorStatus.BAD_REQUEST),

  EMAIL_VERIFICATION_RESEND_TOO_SOON(
      "EMAIL_VERIFICATION_RESEND_TOO_SOON", "인증번호는 1분 후 다시 요청할 수 있습니다.", ErrorStatus.BAD_REQUEST),

  EMAIL_VERIFICATION_TOKEN_INVALID(
      "EMAIL_VERIFICATION_TOKEN_INVALID", "이메일 인증 정보가 유효하지 않습니다.", ErrorStatus.BAD_REQUEST),

  EMAIL_ALREADY_EXISTS("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", ErrorStatus.CONFLICT),

  NICKNAME_ALREADY_EXISTS("NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다.", ErrorStatus.CONFLICT),

  EMAIL_DELIVERY_FAILED(
      "EMAIL_DELIVERY_FAILED", "인증 이메일 발송에 실패했습니다.", ErrorStatus.INTERNAL_SERVER_ERROR),

  EMAIL_VERIFICATION_SEND_LIMIT_EXCEEDED(
      "EMAIL_VERIFICATION_SEND_LIMIT_EXCEEDED",
      "이메일 인증 요청 횟수를 초과했습니다. 30분 후 다시 시도해 주세요.",
      ErrorStatus.BAD_REQUEST),

  REFRESH_TOKEN_INVALID(
      "REFRESH_TOKEN_INVALID", "Refresh Token이 유효하지 않습니다.", ErrorStatus.UNAUTHORIZED),

  GOOGLE_SIGNUP_TOKEN_INVALID(
      "GOOGLE_SIGNUP_TOKEN_INVALID",
      "Google 회원가입 인증 정보가 만료되었거나 유효하지 않습니다.",
      ErrorStatus.BAD_REQUEST),

  GOOGLE_ACCOUNT_ALREADY_EXISTS(
      "GOOGLE_ACCOUNT_ALREADY_EXISTS", "이미 가입된 Google 계정입니다.", ErrorStatus.CONFLICT),

  GOOGLE_AUTHENTICATION_FAILED(
      "GOOGLE_AUTHENTICATION_FAILED", "Google 인증에 실패했습니다.", ErrorStatus.UNAUTHORIZED),

  GOOGLE_AUTH_SERVER_ERROR(
      "GOOGLE_AUTH_SERVER_ERROR", "Google 인증 서버에 연결할 수 없습니다.", ErrorStatus.INTERNAL_SERVER_ERROR),

  KAKAO_SIGNUP_TOKEN_INVALID(
      "KAKAO_SIGNUP_TOKEN_INVALID", "Kakao 회원가입 인증 정보가 만료되었거나 유효하지 않습니다.", ErrorStatus.BAD_REQUEST),

  KAKAO_AUTHENTICATION_FAILED(
      "KAKAO_AUTHENTICATION_FAILED", "Kakao 인증에 실패했습니다.", ErrorStatus.UNAUTHORIZED),

  KAKAO_AUTH_SERVER_ERROR(
      "KAKAO_AUTH_SERVER_ERROR", "Kakao 인증 서버에 연결할 수 없습니다.", ErrorStatus.INTERNAL_SERVER_ERROR),

  KAKAO_ACCOUNT_ALREADY_EXISTS(
      "KAKAO_ACCOUNT_ALREADY_EXISTS", "이미 가입된 Kakao 계정입니다.", ErrorStatus.CONFLICT);

  private final String code;
  private final String message;
  private final ErrorStatus status;

  MemberErrorCode(String code, String message, ErrorStatus status) {
    this.code = code;
    this.message = message;
    this.status = status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String message() {
    return message;
  }

  @Override
  public ErrorStatus status() {
    return status;
  }
}
