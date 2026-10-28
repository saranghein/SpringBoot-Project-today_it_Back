package com.todayit.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.KakaoIdentity;
import com.todayit.member.exception.MemberErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KakaoSignupTokenServiceTest {
  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  private KakaoSignupTokenService service;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();

    service = new KakaoSignupTokenService(redisTemplate, objectMapper);
  }

  @Test
  @DisplayName("이메일이 없는 Kakao 회원에게 30분짜리 가입 토큰을 발급한다")
  void issuesSignupTokenWithoutEmail() throws Exception {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    // When
    String token = service.issue(identity);

    // Then
    assertThat(token).matches("[A-Za-z0-9_-]{43}");

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

    verify(valueOperations)
        .set(keyCaptor.capture(), valueCaptor.capture(), eq(Duration.ofMinutes(30)));

    // 토큰 원문이 아니라 해시된 Key로 저장되었는지 확인
    assertThat(keyCaptor.getValue()).isEqualTo(expectedRedisKey(token));

    // Redis에 저장된 사용자 정보 확인
    KakaoIdentity savedIdentity =
        objectMapper.readValue(valueCaptor.getValue(), KakaoIdentity.class);

    assertThat(savedIdentity).isEqualTo(identity);
  }

  @Test
  @DisplayName("Kakao 이메일이 인증되어 있어도 Redis에 저장하지 않는다")
  void excludesVerifiedKakaoEmail() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", "test@example.com", true);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    // When
    service.issue(identity);

    // Then
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

    verify(valueOperations).set(anyString(), payloadCaptor.capture(), eq(Duration.ofMinutes(30)));

    KakaoIdentity savedIdentity =
        objectMapper.readValue(payloadCaptor.getValue(), KakaoIdentity.class);

    assertThat(savedIdentity).isEqualTo(new KakaoIdentity("123456789", null, false));
  }

  @Test
  @DisplayName("Kakao 사용자 ID가 없으면 토큰을 발급하지 않는다")
  void rejectsMissingProviderUserId() {
    // Given
    KakaoIdentity identity = new KakaoIdentity(null, null, false);

    // When & Then
    assertThatThrownBy(() -> service.issue(identity)).isInstanceOf(IllegalArgumentException.class);

    // 잘못된 사용자 정보는 Redis에 저장하지 않음
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("정상적인 가입 토큰으로 Kakao 사용자 정보를 조회한다")
  void getsVerifiedIdentity() {
    // Given
    String signupToken = "a".repeat(43);

    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(identity));

    // When
    KakaoIdentity result = service.getVerifiedIdentity(signupToken);

    // Then
    assertThat(result).isEqualTo(identity);

    // 조회만으로 가입 토큰을 삭제하지 않음
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  @DisplayName("만료된 Kakao 가입 토큰은 커스텀 에러로 처리한다")
  void rejectsExpiredSignupToken() {
    // Given
    String signupToken = "a".repeat(43);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn(null);

    // When & Then
    assertThatThrownBy(() -> service.getVerifiedIdentity(signupToken))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.KAKAO_SIGNUP_TOKEN_INVALID));
  }

  @Test
  @DisplayName("가입 토큰 형식이 잘못되면 Redis를 조회하지 않는다")
  void rejectsInvalidSignupTokenFormat() {
    // Given
    String signupToken = "invalid-token";

    // When & Then
    assertThatThrownBy(() -> service.getVerifiedIdentity(signupToken))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.KAKAO_SIGNUP_TOKEN_INVALID));

    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("Redis에 저장된 사용자 정보가 유효하지 않으면 거부한다")
  void rejectsInvalidStoredIdentity() {
    // Given
    String signupToken = "a".repeat(43);

    KakaoIdentity invalidIdentity = new KakaoIdentity(null, null, false);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.get(anyString()))
        .thenReturn(objectMapper.writeValueAsString(invalidIdentity));

    // When & Then
    assertThatThrownBy(() -> service.getVerifiedIdentity(signupToken))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.KAKAO_SIGNUP_TOKEN_INVALID));
  }

  @Test
  @DisplayName("가입 완료 후 임시 토큰을 Redis에서 삭제한다")
  void invalidatesSignupToken() throws Exception {
    // Given
    String signupToken = "a".repeat(43);

    // When
    service.invalidate(signupToken);

    // Then
    verify(redisTemplate).delete(expectedRedisKey(signupToken));
  }

  /**
   * 가입 토큰으로 예상 Redis Key를 생성합니다.
   *
   * @param token 회원가입 임시 토큰
   * @return 예상 Redis Key
   * @throws Exception 해시 계산에 실패한 경우
   */
  private String expectedRedisKey(String token) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));

    return "auth:oauth-signup:kakao:"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }
}
