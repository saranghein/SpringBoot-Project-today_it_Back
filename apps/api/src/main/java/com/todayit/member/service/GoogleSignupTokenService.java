package com.todayit.member.service;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.GoogleIdentity;
import com.todayit.member.exception.MemberErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 신규 Google 사용자의 회원가입용 임시 토큰을 관리합니다. */
@Service
public class GoogleSignupTokenService {

  private static final int TOKEN_BYTE_LENGTH = 32;
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
  private static final String TOKEN_KEY_PREFIX = "auth:oauth-signup:google:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 임시 토큰 저장에 필요한 객체를 주입받습니다.
   *
   * @param redisTemplate Redis 접근 객체
   * @param objectMapper Google 인증 정보 직렬화 객체
   */
  public GoogleSignupTokenService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 신규 Google 사용자에게 회원가입용 임시 토큰을 발급합니다.
   *
   * <p>토큰 원문은 Redis에 저장하지 않고 해시값을 Key로 사용합니다. Google 계정 정보는 JSON으로 저장하며 30분 후 자동 만료됩니다.
   *
   * @param identity 검증된 Google 계정 정보
   * @return 회원가입용 임시 토큰 원문
   * @throws IllegalArgumentException Google 계정 정보가 유효하지 않은 경우
   * @throws IllegalStateException 토큰 생성 또는 정보 직렬화에 실패한 경우
   */
  public String issue(GoogleIdentity identity) {
    // Google 계정 고유 ID 검증
    if (!isValidIdentity(identity)) {
      throw new IllegalArgumentException("유효한 Google 인증 정보가 필요합니다.");
    }

    byte[] tokenBytes = new byte[TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(tokenBytes);

    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

    String redisKey = keyOf(token);

    String payload;

    try {
      // Google 고유 ID만 Redis에 저장
      GoogleIdentity signupIdentity = new GoogleIdentity(identity.providerUserId(), null, false);

      payload = objectMapper.writeValueAsString(signupIdentity);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Google 회원가입 정보 직렬화에 실패했습니다.", exception);
    }

    redisTemplate.opsForValue().set(redisKey, payload, TOKEN_TTL);

    return token;
  }

  /**
   * Google 회원가입용 임시 토큰을 검증하고 사용자 정보를 조회합니다.
   *
   * <p>토큰 조회 시 Redis 데이터를 삭제하지 않습니다. 실제 회원가입 DB 트랜잭션이 성공한 뒤 별도로 무효화해야 합니다.
   *
   * @param signupToken Google 로그인에서 발급한 임시 가입 토큰
   * @return Google에서 인증된 사용자 정보
   * @throws BusinessException 토큰이 만료되었거나 유효하지 않은 경우
   */
  public GoogleIdentity getVerifiedIdentity(String signupToken) {

    // 토큰 원문이 아닌 SHA-256 해시를 사용해 Redis Key 생성
    String redisKey = keyOf(signupToken);

    // Redis에서 Google 사용자 인증 정보 조회
    String payload = redisTemplate.opsForValue().get(redisKey);

    // 토큰이 없으면 만료되었거나 이미 삭제된 상태
    if (payload == null || payload.isBlank()) {
      throw new BusinessException(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID);
    }

    GoogleIdentity identity;

    try {
      // JSON으로 저장했던 Google 계정 정보를 다시 객체로 변환
      identity = objectMapper.readValue(payload, GoogleIdentity.class);
    } catch (JacksonException exception) {
      throw new BusinessException(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID);
    }

    // 복원한 데이터가 정상적인 Google 인증 정보인지 재확인
    if (!isValidIdentity(identity)) {
      throw new BusinessException(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID);
    }

    return new GoogleIdentity(identity.providerUserId(), null, false);
  }

  /**
   * 회원가입에 사용한 Google 임시 토큰을 무효화합니다.
   *
   * <p>회원 DB 트랜잭션이 성공한 뒤 호출해야 합니다.
   *
   * @param signupToken 무효화할 Google 회원가입용 임시 토큰
   */
  public void invalidate(String signupToken) {

    // 가입 완료 후 기존 임시 토큰을 사용할 수 없도록 삭제
    redisTemplate.delete(keyOf(signupToken));
  }

  /**
   * 회원가입 토큰을 검증하고 Redis Key로 변환합니다.
   *
   * @param token 회원가입 임시 토큰 원문
   * @return SHA-256 해시값이 포함된 Redis Key
   * @throws BusinessException 토큰 형식이 유효하지 않은 경우
   */
  private String keyOf(String token) {

    // 기존 issue()가 발급하는 토큰은 Base64URL 43자리 문자열
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
      throw new BusinessException(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID);
    }

    return TOKEN_KEY_PREFIX + hash(token);
  }

  /**
   * 토큰을 SHA-256 해시로 변환합니다.
   *
   * @param token 해시할 토큰
   * @return URL-safe Base64 형식의 해시
   * @throws IllegalStateException SHA-256 알고리즘을 사용할 수 없는 경우
   */
  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));

      return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("회원가입 토큰 해시 생성에 실패했습니다.", exception);
    }
  }

  /**
   * Google 사용자 고유 ID가 유효한지 확인합니다.
   *
   * @param identity Google 사용자 정보
   * @return 고유 ID가 유효하면 true
   */
  private boolean isValidIdentity(GoogleIdentity identity) {
    return identity != null
        && identity.providerUserId() != null
        && !identity.providerUserId().isBlank()
        && identity.providerUserId().length() <= 255;
  }
}
