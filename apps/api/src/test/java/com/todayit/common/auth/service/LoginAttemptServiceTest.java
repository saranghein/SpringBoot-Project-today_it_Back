package com.todayit.common.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

  // Redis 대체 역할
  @Mock private StringRedisTemplate redisTemplate;

  // Redis의 String 값 연산
  @Mock private ValueOperations<String, String> valueOperations;

  private LoginAttemptService loginAttemptService;

  @BeforeEach
  void setUp() {
    loginAttemptService = new LoginAttemptService(redisTemplate);
  }

  @Test
  @DisplayName("첫 로그인 실패 시 실패 횟수를 1 증가시키고 1을 반환한다")
  void recordsFirstLoginFailure() {
    // Given
    String email = "test@test.com";
    String key = "auth:login:attempt:" + email;

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment(key)).thenReturn(1L);

    // When
    long failureCount = loginAttemptService.recordFailure(email);

    // Then
    assertEquals(1L, failureCount);
    verify(valueOperations).increment(key);
  }

  @Test
  @DisplayName("로그인 실패 기록이 없으면 잠긴 상태가 아니다")
  void returnsFalseWhenFailureRecordDoesNotExist() {
    // Given
    String email = "test@test.com";
    String key = "auth:login:attempt:" + email;

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(null);

    // When
    boolean locked = loginAttemptService.isLocked(email);

    // Then
    assertFalse(locked);
  }

  @Test
  @DisplayName("로그인 실패 횟수가 5회면 잠긴 상태이다")
  void returnsTrueWhenFailureCountReachesMaximum() {
    // Given
    String email = "test@test.com";
    String key = "auth:login:attempt:" + email;

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn("5");

    // When
    boolean locked = loginAttemptService.isLocked(email);

    // Then
    assertTrue(locked);
  }

  @Test
  @DisplayName("로그인 실패 횟수가 4회면 아직 잠긴 상태가 아니다")
  void returnsFalseWhenFailureCountIsBelowMaximum() {
    // Given
    String email = "test@test.com";
    String key = "auth:login:attempt:" + email;

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn("4");

    // When
    boolean locked = loginAttemptService.isLocked(email);

    // Then
    assertFalse(locked);
  }

  @Test
  @DisplayName("로그인 실패 기록을 초기화하면 Redis 기록을 삭제한다")
  void resetsFailureCount() {
    // Given
    String email = "test@test.com";
    String key = "auth:login:attempt:" + email;

    // When
    loginAttemptService.resetFailures(email);

    // Then
    verify(redisTemplate).delete(key);
  }
}
