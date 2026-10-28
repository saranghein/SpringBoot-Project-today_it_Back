package com.todayit.member.client.external;

/**
 * Kakao에서 조회한 사용자 정보입니다.
 *
 * @param providerUserId Kakao 회원 고유 ID
 * @param email Kakao 계정 이메일
 * @param emailVerified 이메일 인증 및 유효 여부
 */
public record KakaoIdentity(String providerUserId, String email, boolean emailVerified) {}
