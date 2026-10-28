package com.todayit.member.client.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.todayit.member.client.external.GoogleOAuthException.Reason;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GoogleOAuthIdTokenTest {

  private static final String CLIENT_ID = "test-client-id";
  private static final String CLIENT_SECRET = "test-client-secret";
  private static final String REDIRECT_URI = "http://localhost:3000/oauth/google/callback";

  private KeyPair trustedKeys;

  @BeforeEach
  void setUp() throws Exception {
    // 테스트에서 사용할 RSA 공개키와 개인키 생성
    trustedKeys = generateKeyPair();
  }

  /**
   * RSA 키 쌍을 생성합니다.
   *
   * @return 테스트용 RSA 키 쌍
   */
  private KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  /**
   * 테스트용 Google ID Token을 생성하고 RSA 개인키로 서명합니다.
   *
   * @param signingKeys 서명에 사용할 키
   * @param issuer 토큰 발급자
   * @param audience 토큰 사용 대상
   * @param authorizedParty 승인된 클라이언트
   * @param expiresAt 토큰 만료 시간
   * @return RSA 서명이 포함된 JWT 문자열
   */
  private String signedIdToken(
      KeyPair signingKeys,
      String issuer,
      String audience,
      String authorizedParty,
      Instant expiresAt)
      throws JOSEException {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("google-user-123")
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(300)))
            .expirationTime(Date.from(expiresAt))
            .claim("email", "test@gmail.com")
            .claim("email_verified", true);

    if (authorizedParty != null) {
      claims.claim("azp", authorizedParty);
    }

    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());

    // RSA 개인키로 JWT 서명
    jwt.sign(new RSASSASigner((RSAPrivateKey) signingKeys.getPrivate()));

    return jwt.serialize();
  }

  /**
   * Google 토큰 발급 응답을 모의하고 실제 RSA 서명 검증기를 사용합니다.
   *
   * @param idToken 검증할 JWT
   * @return 테스트용 Google OAuth 클라이언트
   */
  private GoogleOAuthClient createClient(String idToken) {
    // 외부 Google 서버 대신 테스트용 응답 반환
    WebClient.Builder builder =
        WebClient.builder()
            .exchangeFunction(
                request ->
                    Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"id_token\":\"" + idToken + "\"}")
                            .build()));

    // 신뢰할 RSA 공개키로 JWT 서명과 만료 시간 검증
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) trustedKeys.getPublic()).build();

    return new GoogleOAuthClient(builder, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, decoder);
  }

  /**
   * 유효한 Google ID Token을 생성합니다.
   *
   * @return 유효한 JWT 문자열
   */
  private String validIdToken(String issuer) throws JOSEException {
    return signedIdToken(
        trustedKeys, issuer, CLIENT_ID, CLIENT_ID, Instant.now().plusSeconds(3600));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://accounts.google.com", "accounts.google.com"})
  @DisplayName("Google에서 허용하는 두 issuer는 인증에 성공한다")
  void authenticatesValidGoogleIdToken(String issuer) throws Exception {
    // Given
    GoogleOAuthClient client = createClient(validIdToken(issuer));

    // When
    GoogleIdentity identity = client.authenticate("authorization-code", REDIRECT_URI);

    // Then
    assertEquals("google-user-123", identity.providerUserId());
    assertNull(identity.email());
    assertFalse(identity.emailVerified());
  }

  @ParameterizedTest(name = "{index}: iss={0}, aud={1}, azp={2}, expired={3}")
  @CsvSource({
    "https://other-provider.example, test-client-id, test-client-id, false",
    "https://accounts.google.com, another-client-id, test-client-id, false",
    "https://accounts.google.com, test-client-id, another-client-id, false",
    "https://accounts.google.com, test-client-id, test-client-id, true"
  })
  @DisplayName("Google ID Token의 발급자·대상·승인 앱·만료 시간을 검증한다")
  void rejectsInvalidClaims(String issuer, String audience, String authorizedParty, boolean expired)
      throws JOSEException {

    // Given
    Instant expiresAt = Instant.now().plusSeconds(expired ? -300 : 3600);

    String token = signedIdToken(trustedKeys, issuer, audience, authorizedParty, expiresAt);

    // When & Then
    assertInvalidIdToken(token);
  }

  @Test
  @DisplayName("신뢰하지 않는 개인키로 서명한 ID Token은 거부한다")
  void rejectsInvalidSignature() throws Exception {
    // Given
    KeyPair untrustedKeys = generateKeyPair();

    String token =
        signedIdToken(
            untrustedKeys,
            "https://accounts.google.com",
            CLIENT_ID,
            CLIENT_ID,
            Instant.now().plusSeconds(3600));

    // When & Then
    assertInvalidIdToken(token);
  }

  /** 유효하지 않은 ID Token이 인증 실패로 처리되는지 확인합니다. */
  private void assertInvalidIdToken(String token) {
    GoogleOAuthException exception =
        assertThrows(
            GoogleOAuthException.class,
            () -> createClient(token).authenticate("authorization-code", REDIRECT_URI));

    assertEquals(Reason.INVALID_ID_TOKEN, exception.getReason());
  }
}
