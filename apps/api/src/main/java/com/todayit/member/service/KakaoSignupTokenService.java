package com.todayit.member.service;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.KakaoIdentity;
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

/** 신규 Kakao 사용자의 회원가입용 임시 토큰을 관리합니다. */
@Service
public class KakaoSignupTokenService {
  private static final int TOKEN_BYTE_LENGTH = 32;
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
  private static final String TOKEN_KEY_PREFIX = "auth:oauth-signup:kakao:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 임시 토큰 관리에 필요한 객체를 주입받습니다.
   *
   * @param redisTemplate Redis 접근 객체
   * @param objectMapper Kakao 인증 정보 직렬화 객체
   */
  public KakaoSignupTokenService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 신규 Kakao 사용자에게 회원가입용 임시 토큰을 발급합니다.
   *
   * <p>토큰 원문은 Redis에 저장하지 않고 SHA-256 해시값을 Key로 사용합니다. Kakao 인증 정보는 JSON으로 저장하며 30분 후 자동 만료됩니다.
   *
   * @param identity Kakao에서 조회한 사용자 정보
   * @return 회원가입용 임시 토큰 원문
   * @throws IllegalArgumentException Kakao 사용자 ID가 유효하지 않은 경우
   * @throws IllegalStateException SHA-256 알고리즘을 사용할 수 없는 경우
   */
  public String issue(KakaoIdentity identity) {
    // 사용자 ID를 검증하고 이메일 정보를 정리
    KakaoIdentity verifiedIdentity = normalizeIdentity(identity);

    // 32바이트 보안 난수 생성
    byte[] tokenBytes = new byte[TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(tokenBytes);

    // URL에 안전한 형식의 임시 토큰 생성
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

    // 토큰 원문을 SHA-256으로 해싱하여 Redis Key 생성
    String redisKey = keyOf(token);

    // Kakao 인증 정보를 JSON 문자열로 변환
    String payload = objectMapper.writeValueAsString(verifiedIdentity);

    // Redis에 인증 정보를 30분간 저장
    redisTemplate.opsForValue().set(redisKey, payload, TOKEN_TTL);

    return token;
  }

  /**
   * Kakao 회원가입 임시 토큰을 검증하고 인증 정보를 조회합니다.
   *
   * <p>조회 시에는 Redis 데이터를 삭제하지 않습니다. 실제 DB 회원가입이 성공한 후 별도로 무효화해야 합니다.
   *
   * @param signupToken Kakao 로그인에서 발급한 임시 가입 토큰
   * @return 검증된 Kakao 사용자 정보
   * @throws BusinessException 가입 토큰 또는 인증 정보가 유효하지 않은 경우
   */
  public KakaoIdentity getVerifiedIdentity(String signupToken) {
    // 임시 토큰을 해싱해 Redis Key 생성
    String redisKey = keyOf(signupToken);

    // Redis에 저장된 Kakao 인증 정보 조회
    String payload = redisTemplate.opsForValue().get(redisKey);

    // 토큰이 존재하지 않거나 만료된 경우
    if (payload == null || payload.isBlank()) {
      throw invalidSignupToken();
    }

    // JSON을 Kakao 사용자 정보로 복원
    KakaoIdentity identity;

    try {
      identity = objectMapper.readValue(payload, KakaoIdentity.class);
    } catch (JacksonException exception) {
      throw invalidSignupToken();
    }

    // Redis에서 복원한 인증 정보의 유효성 재검증
    if (!isValidIdentity(identity)) {
      throw invalidSignupToken();
    }

    return normalizeIdentity(identity);
  }

  /**
   * 회원가입에 사용한 임시 토큰을 무효화합니다.
   *
   * <p>회원가입 DB 트랜잭션이 성공한 이후 호출해야 합니다.
   *
   * @param signupToken 삭제할 회원가입 임시 토큰
   * @throws BusinessException 토큰 형식이 유효하지 않은 경우
   */
  public void invalidate(String signupToken) {
    // 가입 완료 후 임시 토큰 삭제
    redisTemplate.delete(keyOf(signupToken));
  }

  /**
   * Kakao 회원가입에 필요한 고유 ID만 남깁니다.
   *
   * @param identity Kakao 사용자 정보
   * @return 이메일을 제외한 인증 정보
   */
  private KakaoIdentity normalizeIdentity(KakaoIdentity identity) {
    // Kakao 사용자 고유 ID 검증
    if (identity == null
        || identity.providerUserId() == null
        || identity.providerUserId().isBlank()
        || identity.providerUserId().length() > 255) {
      throw new IllegalArgumentException("유효한 Kakao 사용자 ID가 필요합니다.");
    }

    // 이메일은 저장하지 않고 고유 ID만 유지
    return new KakaoIdentity(identity.providerUserId(), null, false);
  }

  /**
   * Redis에서 가져온 Kakao 사용자 ID를 검증합니다.
   *
   * @param identity 복원한 Kakao 사용자 정보
   * @return 유효한 인증 정보이면 true
   */
  private boolean isValidIdentity(KakaoIdentity identity) {
    return identity != null
        && identity.providerUserId() != null
        && !identity.providerUserId().isBlank()
        && identity.providerUserId().length() <= 255;
  }

  /**
   * 회원가입 토큰을 검증하고 Redis Key로 변환합니다.
   *
   * @param token 회원가입 임시 토큰 원문
   * @return SHA-256 해시값이 포함된 Redis Key
   * @throws BusinessException 토큰 형식이 유효하지 않은 경우
   */
  private String keyOf(String token) {
    // 32바이트 난수를 Base64URL로 인코딩하면 43자리
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
      throw invalidSignupToken();
    }

    return TOKEN_KEY_PREFIX + hash(token);
  }

  /**
   * 회원가입 토큰을 SHA-256으로 해싱합니다.
   *
   * @param token 해싱할 토큰
   * @return Base64URL 형식의 해시 문자열
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
   * 유효하지 않은 Kakao 회원가입 토큰에 대한 업무 예외를 생성합니다.
   *
   * @return Kakao 회원가입 토큰 오류
   */
  private BusinessException invalidSignupToken() {
    return new BusinessException(MemberErrorCode.KAKAO_SIGNUP_TOKEN_INVALID);
  }
}
