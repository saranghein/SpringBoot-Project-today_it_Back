package com.todayit.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 로그인에 필요한 회원 정보를 나타냅니다. */
@Entity
@Table(name = "member")
public class Member {
  @Id
  @Column(name = "member_id", length = 36)
  private String id;

  // LOCAL 회원만 이메일 저장, 소셜 회원은 null
  @Column(name = "email", length = 100)
  private String email;

  @Column(name = "password", length = 100)
  private String password;

  @Column(name = "nickname", nullable = false, length = 50)
  private String nickname;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 20)
  private MemberProvider provider;

  // Google·Kakao에서 발급한 사용자 고유 식별자
  @Column(name = "provider_user_id", length = 255)
  private String providerUserId;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected Member() {}

  private Member(
      String id,
      String email,
      String password,
      MemberProvider provider,
      String nickname,
      boolean active,
      LocalDateTime createdAt,
      String providerUserId) {

    this.id = id;
    // LOCAL 회원만 이메일을 저장
    // Google·Kakao 회원은 고유 ID로 식별
    this.email = provider == MemberProvider.LOCAL ? email : null;
    this.password = password;
    this.provider = provider;
    this.nickname = nickname;
    this.active = active;
    this.createdAt = createdAt;

    // Google 등 소셜 서비스에서 발급한 사용자 고유 식별자
    this.providerUserId = providerUserId;
  }

  /**
   * 이메일과 비밀번호를 사용하는 로컬 회원을 생성합니다.
   *
   * @param email 회원 이메일
   * @param encodedPassword 암호화된 비밀번호
   * @param nickname 회원 닉네임
   * @return 생성된 로컬 회원
   */
  public static Member createLocal(String email, String encodedPassword, String nickname) {
    return new Member(
        UUID.randomUUID().toString(),
        email,
        encodedPassword,
        MemberProvider.LOCAL,
        nickname,
        true,
        LocalDateTime.now(),
        null);
  }

  /**
   * Google 인증을 완료한 사용자의 회원 정보를 생성합니다.
   *
   * <p>Google 회원은 자체 비밀번호를 저장하지 않고, Google 계정 고유 ID를 provider_user_id에 저장합니다.
   *
   * @param providerUserId Google 계정 고유 식별자(sub)
   * @param nickname 사용자가 등록한 닉네임
   * @return 생성된 Google 회원
   */
  public static Member createGoogle(String providerUserId, String nickname) {

    return new Member(
        UUID.randomUUID().toString(),
        null,
        null,
        MemberProvider.GOOGLE,
        nickname,
        true,
        LocalDateTime.now(),
        providerUserId);
  }

  /**
   * Kakao 인증을 완료한 사용자의 회원 정보를 생성합니다.
   *
   * <p>Kakao 회원은 자체 비밀번호를 저장하지 않으며, 이메일이 없어도 Kakao 계정 고유 ID로 식별합니다.
   *
   * @param providerUserId Kakao 계정 고유 식별자
   * @param nickname 사용자가 등록한 닉네임
   * @return 생성된 Kakao 회원
   */
  public static Member createKakao(String providerUserId, String nickname) {

    return new Member(
        UUID.randomUUID().toString(),
        null,
        null,
        MemberProvider.KAKAO,
        nickname,
        true,
        LocalDateTime.now(),
        providerUserId);
  }

  public String getId() {
    return id;
  }

  public String getPassword() {
    return password;
  }

  public String getEmail() {
    return email;
  }

  public String getNickname() {
    return nickname;
  }

  public MemberProvider getProvider() {
    return provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public boolean isActive() {
    return active;
  }
}
