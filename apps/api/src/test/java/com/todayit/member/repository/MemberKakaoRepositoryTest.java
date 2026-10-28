package com.todayit.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 이메일이 없는 Kakao 회원의 저장과 조회를 검증합니다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class MemberKakaoRepositoryTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("todayit")
          .withUsername("todayit_user")
          .withPassword("todayit1234")
          .withUrlParam("currentSchema", "todayit");

  @Autowired private MemberRepository memberRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("이메일이 없는 서로 다른 Kakao 회원을 저장하고 조회한다")
  void savesKakaoMembersWithoutEmail() {
    // Given
    String firstKakaoId = "kakao-" + UUID.randomUUID();
    String secondKakaoId = "kakao-" + UUID.randomUUID();

    Member first = Member.createKakao(firstKakaoId, "카카오회원1");

    Member second = Member.createKakao(secondKakaoId, "카카오회원2");

    // When
    memberRepository.saveAndFlush(first);
    memberRepository.saveAndFlush(second);

    var firstMember =
        memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, firstKakaoId);

    var secondMember =
        memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, secondKakaoId);

    // Then
    assertThat(firstMember).isPresent();
    assertThat(secondMember).isPresent();

    assertThat(firstMember.orElseThrow().getEmail()).isNull();
    assertThat(secondMember.orElseThrow().getEmail()).isNull();

    // 실제 DB에도 이메일이 NULL로 저장되었는지 확인
    String storedEmail =
        jdbcTemplate.queryForObject(
            "SELECT email FROM member WHERE member_id = ?", String.class, first.getId());

    assertThat(storedEmail).isNull();
  }

  @Test
  @DisplayName("동일한 Kakao 사용자 ID는 중복 저장할 수 없다")
  void rejectsDuplicateKakaoProviderUserId() {
    // Given
    String kakaoId = "123456789";

    Member first = Member.createKakao(kakaoId, "카카오회원1");

    Member second = Member.createKakao(kakaoId, "카카오회원2");

    memberRepository.saveAndFlush(first);

    // When & Then
    assertThatThrownBy(() -> memberRepository.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
