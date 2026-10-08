package com.todayit.common.auth.config;

import com.todayit.common.auth.filter.JwtAuthenticationFilter;
import com.todayit.common.auth.handler.RestAccessDeniedHandler;
import com.todayit.common.auth.handler.RestAuthenticationEntryPoint;
import com.todayit.common.auth.jwt.JwtProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Spring Security 공통 설정 SecurityFilterChain -> 인증 방식, 세션 정책, 인증 인가 실패 처리, API 접근 정책 설정됨 */
@Configuration
@EnableConfigurationProperties({CorsProperties.class, JwtProperties.class})
public class SecurityConfig {

  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  /**
   * 인증 실패, 인가 실패 Handler 주입
   *
   * @param authenticationEntryPoint 인증X 401 응답처리 handler
   * @param accessDeniedHandler 권한X 403 응답 처리 handler
   */
  public SecurityConfig(
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler) {
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.accessDeniedHandler = accessDeniedHandler;
  }

  /**
   * API에 적용할 CORS 정책 구성 CorsProperties → 어느 Origin을 허용? CorsConfiguration → 어떤
   * Method/Header/Cookie를 허용? UrlBasedCorsConfigurationSource → 그 규칙을 어느 URL에 적용?
   *
   * @param corsProperties CORS 설정값
   * @return CORS 설정
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
    CorsConfiguration configuration = new CorsConfiguration();

    // corsProperties에서 허용할 Origin 설정 받음
    configuration.setAllowedOrigins(corsProperties.origins());

    // 사용하는 HTTP Method만 허용
    configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));

    // JSON 요청, Bearer Token 인증이 필요한 Header만 허용
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

    // 인증 정보를 Authorization Header로 전달함 -> Cross-Origin cookie 전송 허용 안함
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

    // API prefix에 해당하는 요청만 CORS 정책 적용
    source.registerCorsConfiguration("/api/v1/**", configuration);

    return source;
  }

  /**
   * Spring Security Chain 구성
   *
   * @param http Spring Security HTTP 설정
   * @param corsConfigurationSource CORS 설정
   * @param jwtAuthenticationFilter Access Token 인증 필터
   * @return SecurityFilterChain
   * @throws Exception SecurityFilterChain 구성 실패 시
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CorsConfigurationSource corsConfigurationSource,
      JwtAuthenticationFilter jwtAuthenticationFilter)
      throws Exception {
    return http
        // Cross-Origin 요청 처리
        .cors(cors -> cors.configurationSource(corsConfigurationSource))

        // JWT 기반 인증에서 서버 세션에 인증 정보 저장하지 않음
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // Access Token은 cookie가 아닌 Authorization Bearer Header로 전달
        // Refresh Token 도입 시 수정 예정
        .csrf(csrf -> csrf.disable())

        // 인증이 필요 없는 API, 인증이 필요한 API를 구분
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // 로그인, 회원가입 -> 인증 안 된 사용자가 호출해야함
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/signup")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/places")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/places/*/location")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/places/*/images")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/places/*/courses")
                    .permitAll()

                    // 컨테이너 오케스트레이션에서 애플리케이션 생존 여부만 확인
                    .requestMatchers(HttpMethod.GET, "/actuator/health/liveness")
                    .permitAll()

                    // API 명세
                    .requestMatchers(
                        HttpMethod.GET,
                        "/scalar/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml")
                    .permitAll()

                    // 위에를 제외한 모든 요청은 인증된 사용자만 접근 가능하도록 설정
                    .anyRequest()
                    .authenticated())

        // Spring Security 단계에서 발생한 인증(401), 권한(403) 실패 응답을 처리
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))

        // Controller 전에 JWT 인증 처리
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * 로컬 로그인 비밀번호 암호화, 검증에 사용할 PasswordEncoder
   *
   * @return BCrypt 기반 PasswordEncoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
