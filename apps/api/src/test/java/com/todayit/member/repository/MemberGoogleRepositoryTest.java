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

/** 소셜 로그인 제공자의 고유 식별자로 회원을 조회하는 테스트입니다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class MemberGoogleRepositoryTest {

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
  @DisplayName("Google 회원은 비밀번호 없이 provider_user_id를 저장한다")
  void savesGoogleMember() {
    // Given
    Member member = Member.createGoogle("google-new-user-123", "구글테스트");

    // When
    memberRepository.saveAndFlush(member);

    // Then
    assertThat(member.getProvider()).isEqualTo(MemberProvider.GOOGLE);
    assertThat(member.getPassword()).isNull();
    assertThat(member.getProviderUserId()).isEqualTo("google-new-user-123");
    assertThat(member.getEmail()).isNull();

    // 실제 DB에도 이메일이 NULL로 저장되었는지 확인
    String storedEmail =
        jdbcTemplate.queryForObject(
            "SELECT email FROM member WHERE member_id = ?", String.class, member.getId());

    assertThat(storedEmail).isNull();

    // 저장한 Google 계정이 기존 로그인 조회 조건으로 검색되는지 검증
    var savedMember =
        memberRepository.findByProviderAndProviderUserId(
            MemberProvider.GOOGLE, "google-new-user-123");

    assertThat(savedMember).isPresent();
    assertThat(savedMember.map(Member::getId)).contains(member.getId());
  }

  @Test
  @DisplayName("동일한 Google 사용자 ID는 중복 저장할 수 없다")
  void rejectsDuplicateGoogleProviderUserId() {

    // Given
    String googleUserId = "google-duplicate-" + UUID.randomUUID();

    Member firstMember = Member.createGoogle(googleUserId, "첫번째회원");

    Member secondMember = Member.createGoogle(googleUserId, "두번째회원");

    // 첫 번째 회원 저장
    memberRepository.saveAndFlush(firstMember);

    // When & Then
    // 이메일과 닉네임이 달라도 동일한 Google ID라면 UNIQUE 제약 위반
    assertThatThrownBy(() -> memberRepository.saveAndFlush(secondMember))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
