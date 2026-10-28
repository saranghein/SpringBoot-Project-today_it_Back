package com.todayit.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.common.exception.BusinessException;
import com.todayit.member.client.external.GoogleIdentity;
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
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class GoogleSignupTokenServiceTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  private GoogleSignupTokenService service;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new GoogleSignupTokenService(redisTemplate, objectMapper);
  }

  @Test
  @DisplayName("Google 회원가입 토큰을 해시 Key로 30분간 저장한다")
  void issuesSignupTokenWithThirtyMinuteTtl() throws Exception {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-user-123", "test@gmail.com", true);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    // When
    String token = service.issue(identity);

    // Then
    assertThat(token).matches("[A-Za-z0-9_-]{43}");

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

    verify(valueOperations)
        .set(keyCaptor.capture(), valueCaptor.capture(), eq(Duration.ofMinutes(30)));

    assertThat(keyCaptor.getValue()).isEqualTo(expectedRedisKey(token));

    GoogleIdentity storedIdentity =
        objectMapper.readValue(valueCaptor.getValue(), GoogleIdentity.class);

    assertThat(storedIdentity).isEqualTo(new GoogleIdentity("google-user-123", null, false));
  }

  @Test
  @DisplayName("회원가입 토큰을 발급할 때마다 새로운 값을 생성한다")
  void issuesDifferentTokens() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-user-123", "test@gmail.com", true);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    // When
    String firstToken = service.issue(identity);
    String secondToken = service.issue(identity);

    // Then
    assertThat(firstToken).isNotEqualTo(secondToken);

    verify(valueOperations, times(2))
        .set(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), eq(Duration.ofMinutes(30)));
  }

  @Test
  @DisplayName("Google 사용자 고유 ID가 없으면 가입 토큰을 발급하지 않는다")
  void rejectsMissingGoogleUserId() {
    GoogleIdentity identity = new GoogleIdentity(null, null, false);

    assertThatThrownBy(() -> service.issue(identity)).isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("유효한 Google 가입 토큰으로 사용자 고유 ID를 조회한다")
  void getsVerifiedGoogleIdentity() throws Exception {
    // Given
    String signupToken = "a".repeat(43);

    // 기존 Redis에 이메일이 포함된 인증 정보가 남아 있는 상황
    GoogleIdentity identity = new GoogleIdentity("google-user-123", "test@gmail.com", true);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(identity));

    // When
    GoogleIdentity result = service.getVerifiedIdentity(signupToken);

    // Then
    // 이메일 정보는 제거하고 고유 ID만 반환하는지 확인
    assertThat(result).isEqualTo(new GoogleIdentity("google-user-123", null, false));

    // 인증 정보를 조회하는 것만으로 토큰을 삭제하지 않음
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  @DisplayName("만료되었거나 존재하지 않는 Google 가입 토큰은 거부한다")
  void rejectsExpiredGoogleSignupToken() {
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
                    .isEqualTo(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID));
  }

  @Test
  @DisplayName("Google 가입 토큰 형식이 잘못되면 Redis를 조회하지 않는다")
  void rejectsInvalidGoogleSignupTokenFormat() {
    // When & Then
    assertThatThrownBy(() -> service.getVerifiedIdentity("invalid-token"))
        .isInstanceOf(BusinessException.class);

    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("Google 회원가입 토큰을 무효화하면 Redis에서 삭제한다")
  void invalidatesGoogleSignupToken() throws Exception {
    // Given
    String signupToken = "a".repeat(43);

    // When
    service.invalidate(signupToken);

    // Then
    verify(redisTemplate).delete(expectedRedisKey(signupToken));
  }

  /** 가입 토큰으로 예상 Redis Key를 생성합니다. */
  private String expectedRedisKey(String token) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));

    return "auth:oauth-signup:google:"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }
}
