package com.todayit.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberProvider;
import java.util.Optional;
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
  @DisplayName("이메일이 같아도 Google 고유 ID와 가입 방식으로 회원을 구분한다")
  void findsGoogleMemberByProviderUserId() {
    // Given
    String localMemberId = UUID.randomUUID().toString();
    String googleMemberId = UUID.randomUUID().toString();
    String email = "same@example.com";
    String googleUserId = "google-user-123";

    jdbcTemplate.update(
        """
                INSERT INTO member (
                    member_id, email, password, provider, nickname
                ) VALUES (?, ?, ?, 'LOCAL', ?)
                """,
        localMemberId,
        email,
        "encoded-password",
        "로컬테스트회원");

    jdbcTemplate.update(
        """
                INSERT INTO member (
                    member_id, email, provider, provider_user_id, nickname
                ) VALUES (?, ?, 'GOOGLE', ?, ?)
                """,
        googleMemberId,
        email,
        googleUserId,
        "구글테스트회원");

    // When
    Optional<Member> googleMember =
        memberRepository.findByProviderAndProviderUserId(MemberProvider.GOOGLE, googleUserId);

    Optional<Member> localMember =
        memberRepository.findByProviderAndProviderUserId(MemberProvider.LOCAL, googleUserId);

    Optional<Member> unknownMember =
        memberRepository.findByProviderAndProviderUserId(
            MemberProvider.GOOGLE, "unknown-google-user");

    // Then
    assertThat(googleMember).isPresent();
    assertThat(googleMember.map(Member::getId)).contains(googleMemberId);
    assertThat(googleMember.map(Member::getProviderUserId)).contains(googleUserId);

    assertThat(localMember).isEmpty();
    assertThat(unknownMember).isEmpty();
  }

  @Test
  @DisplayName("Google 회원은 비밀번호 없이 provider_user_id를 저장한다")
  void savesGoogleMember() {
    // Given
    Member member = Member.createGoogle("new-google@example.com", "google-new-user-123", "구글테스트");

    // When
    memberRepository.saveAndFlush(member);

    // Then
    assertThat(member.getProvider()).isEqualTo(MemberProvider.GOOGLE);
    assertThat(member.getPassword()).isNull();
    assertThat(member.getProviderUserId()).isEqualTo("google-new-user-123");

    // 저장한 Google 계정이 기존 로그인 조회 조건으로 검색되는지 검증
    Optional<Member> savedMember =
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

    Member firstMember = Member.createGoogle("first@example.com", googleUserId, "첫번째회원");

    Member secondMember = Member.createGoogle("second@example.com", googleUserId, "두번째회원");

    // 첫 번째 회원 저장
    memberRepository.saveAndFlush(firstMember);

    // When & Then
    // 이메일과 닉네임이 달라도 동일한 Google ID라면 UNIQUE 제약 위반
    assertThatThrownBy(() -> memberRepository.saveAndFlush(secondMember))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
