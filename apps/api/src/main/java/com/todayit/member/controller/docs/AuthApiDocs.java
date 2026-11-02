package com.todayit.member.controller.docs;

import com.todayit.common.openapi.OpenApiConfig;
import com.todayit.common.response.CommonResponse;
import com.todayit.common.response.ErrorResponse;
import com.todayit.member.dto.request.EmailVerificationConfirmRequest;
import com.todayit.member.dto.request.EmailVerificationRequest;
import com.todayit.member.dto.request.GoogleLoginRequest;
import com.todayit.member.dto.request.KakaoLoginRequest;
import com.todayit.member.dto.request.LoginRequest;
import com.todayit.member.dto.request.LogoutRequest;
import com.todayit.member.dto.request.SignupRequest;
import com.todayit.member.dto.request.TokenRefreshRequest;
import com.todayit.member.dto.response.EmailAvailabilityResponse;
import com.todayit.member.dto.response.EmailVerificationConfirmResponse;
import com.todayit.member.dto.response.EmailVerificationResponse;
import com.todayit.member.dto.response.GoogleLoginResponse;
import com.todayit.member.dto.response.KakaoLoginResponse;
import com.todayit.member.dto.response.LoginResponse;
import com.todayit.member.dto.response.SignupResponse;
import com.todayit.member.dto.response.TokenRefreshResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/** 로그인, 회원가입, 이메일 인증 및 토큰 관리 API 명세입니다. */
@Tag(name = "인증", description = "로컬·Google·Kakao 로그인, 회원가입, 이메일 인증 및 토큰 관리 API")
public interface AuthApiDocs {
  /**
   * 이메일과 비밀번호로 로그인합니다.
   *
   * @param request 로그인 요청
   * @return 회원 정보와 인증 토큰
   */
  @Operation(summary = "로그인", description = "로컬 계정의 이메일과 비밀번호를 확인하고 Access/Refresh Token을 발급합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "로그인 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"accessToken\":\"eyJ...\",\"refreshToken\":\"example-refresh-token\",\"expiresIn\":3600,\"member\":{\"memberId\":\"123e4567-e89b-12d3-a456-426614174000\",\"nickname\":\"유저\",\"coupleConnected\":false,\"roles\":[\"USER\"]}}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "이메일 또는 비밀번호 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"이메일은 필수입니다.\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "로그인 실패 또는 로그인 잠금",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "로그인 실패",
                      value =
                          "{\"success\":false,\"code\":\"LOGIN_FAILED\",\"message\":\"이메일 또는 비밀번호가 올바르지 않습니다.\",\"failureCount\":1,\"maxFailureCount\":5}"),
                  @ExampleObject(
                      name = "로그인 잠금",
                      value =
                          "{\"success\":false,\"code\":\"LOGIN_LOCKED\",\"message\":\"로그인 시도 횟수를 초과했습니다. 비밀번호를 변경해주세요.\",\"failureCount\":5,\"maxFailureCount\":5}")
                }))
  })
  ResponseEntity<CommonResponse<LoginResponse>> login(LoginRequest request);

  /**
   * Google 인가 코드로 로그인하거나 신규 회원가입을 준비합니다.
   *
   * @param request Google 인가 코드와 Redirect URI
   * @return 기존 회원의 로그인 토큰 또는 신규 회원 임시 가입 토큰
   */
  @Operation(
      summary = "Google 로그인",
      description =
          "Google Authorization Code를 검증합니다. "
              + "가입된 Google 회원은 Access/Refresh Token을 발급받고, "
              + "신규 사용자는 30분 유효한 signupToken을 발급받습니다. "
              + "같은 이메일의 로컬 계정과 자동으로 연결하지 않습니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "기존 회원 로그인 또는 신규 회원 인증 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = {
                  @ExampleObject(
                      name = "기존 Google 회원",
                      value =
                          """
                                                    {
                                                      "success": true,
                                                      "data": {
                                                        "isNewMember": false,
                                                        "accessToken": "eyJ...",
                                                        "refreshToken": "example-refresh-token",
                                                        "expiresIn": 1800,
                                                        "member": {
                                                          "memberId": "123e4567-e89b-12d3-a456-426614174000",
                                                          "nickname": "유저",
                                                          "coupleConnected": false,
                                                          "roles": ["USER"]
                                                        }
                                                      }
                                                    }
                                                    """),
                  @ExampleObject(
                      name = "신규 Google 사용자",
                      value =
                          """
                                                    {
                                                      "success": true,
                                                      "data": {
                                                        "isNewMember": true,
                                                        "signupToken": "example-signup-token"
                                                      }
                                                    }
                                                    """)
                })),
    @ApiResponse(
        responseCode = "400",
        description = "Google 인가 코드 또는 Redirect URI 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                                          {
                                            "success": false,
                                            "code": "INVALID_REQUEST",
                                            "message": "Google 인가 코드는 필수입니다."
                                          }
                                          """))),
    @ApiResponse(
        responseCode = "401",
        description = "Google 인증 실패",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "Google 인증 실패",
                      value =
                          """
                                                  {
                                                    "success": false,
                                                    "code": "GOOGLE_AUTHENTICATION_FAILED",
                                                    "message": "Google 인증에 실패했습니다."
                                                  }
                                                  """)
                })),
    @ApiResponse(
        responseCode = "500",
        description = "Google 인증 서버 통신 장애",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                                            {
                                              "success": false,
                                              "code": "GOOGLE_AUTH_SERVER_ERROR",
                                              "message": "Google 인증 서버에 연결할 수 없습니다."
                                            }
                                            """)))
  })
  ResponseEntity<CommonResponse<GoogleLoginResponse>> googleLogin(GoogleLoginRequest request);

  /**
   * Kakao 인가 코드로 로그인하거나 신규 회원가입을 준비합니다.
   *
   * @param request Kakao 인가 코드와 Redirect URI
   * @return 기존 회원의 로그인 토큰 또는 신규 회원 임시 가입 토큰
   */
  @Operation(
      summary = "Kakao 로그인",
      description =
          "Kakao Authorization Code로 Access Token을 발급받고 사용자 정보를 조회합니다. "
              + "가입된 Kakao 회원은 Access/Refresh Token을 발급받으며, "
              + "신규 사용자는 30분 유효한 signupToken을 발급받습니다. "
              + "이메일이 없는 Kakao 계정도 가입할 수 있습니다. "
              + "같은 이메일의 LOCAL 또는 GOOGLE 계정과 자동 연결하지 않습니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "기존 회원 로그인 또는 신규 회원 인증 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = {
                  @ExampleObject(
                      name = "기존 Kakao 회원",
                      value =
                          """
                                                    {
                                                      "success": true,
                                                      "data": {
                                                        "isNewMember": false,
                                                        "accessToken": "eyJ...",
                                                        "refreshToken": "example-refresh-token",
                                                        "expiresIn": 1800,
                                                        "member": {
                                                          "memberId": "123e4567-e89b-12d3-a456-426614174000",
                                                          "nickname": "오늘이",
                                                          "coupleConnected": false,
                                                          "roles": ["USER"]
                                                        }
                                                      }
                                                    }
                                                    """),
                  @ExampleObject(
                      name = "신규 Kakao 사용자",
                      value =
                          """
                                                    {
                                                      "success": true,
                                                      "data": {
                                                        "isNewMember": true,
                                                        "signupToken": "example-signup-token"
                                                      }
                                                    }
                                                    """)
                })),
    @ApiResponse(
        responseCode = "400",
        description = "Kakao 인가 코드 또는 Redirect URI 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                                            {
                                              "success": false,
                                              "code": "INVALID_REQUEST",
                                              "message": "Kakao 인가 코드는 필수입니다."
                                            }
                                            """))),
    @ApiResponse(
        responseCode = "401",
        description = "Kakao 인증 실패",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                                            {
                                              "success": false,
                                              "code": "KAKAO_AUTHENTICATION_FAILED",
                                              "message": "Kakao 인증에 실패했습니다."
                                            }
                                            """))),
    @ApiResponse(
        responseCode = "500",
        description = "Kakao 인증 서버 통신 장애",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            """
                                            {
                                              "success": false,
                                              "code": "KAKAO_AUTH_SERVER_ERROR",
                                              "message": "Kakao 인증 서버에 연결할 수 없습니다."
                                            }
                                            """)))
  })
  ResponseEntity<CommonResponse<KakaoLoginResponse>> kakaoLogin(KakaoLoginRequest request);

  /**
   * 현재 기기의 로그인 세션을 종료합니다.
   *
   * @param authentication 인증된 회원
   * @param request 로그아웃할 Refresh Token
   * @return 본문 없는 응답
   */
  @Operation(
      summary = "로그아웃",
      description = "Bearer Access Token으로 인증한 회원의 지정된 Refresh Token 세션을 종료합니다.",
      security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "로그아웃 성공 (응답 본문 없음)"),
    @ApiResponse(
        responseCode = "400",
        description = "Refresh Token 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"Refresh Token은 필수입니다.\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "인증 필요",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
  })
  ResponseEntity<Void> logout(Authentication authentication, LogoutRequest request);

  /**
   * 이메일의 사용 가능 여부를 확인합니다.
   *
   * @param email 확인할 이메일
   * @return 이메일과 사용 가능 여부
   */
  @Operation(summary = "이메일 중복 확인", description = "로컬 계정으로 등록된 이메일인지 확인합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"email\":\"user@example.com\",\"available\":true}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "이메일 누락 또는 100자 초과",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"이메일은 필수입니다.\"}")))
  })
  ResponseEntity<CommonResponse<EmailAvailabilityResponse>> checkEmail(String email);

  /**
   * LOCAL·GOOGLE·KAKAO 회원가입을 처리합니다.
   *
   * @param request 회원가입 요청
   * @return 생성된 회원 정보
   */
  @Operation(
      summary = "회원가입 (LOCAL / GOOGLE / KAKAO)",
      description =
          "LOCAL은 이메일 인증 토큰과 비밀번호를 사용합니다. "
              + "GOOGLE과 KAKAO는 각 소셜 로그인 API에서 발급한 signupToken을 사용합니다. "
              + "GOOGLE과 KAKAO는 이메일을 저장하지 않고 제공자의 고유 ID로 회원을 식별합니다. "
              + "필수 약관은 TOS, ACCOUNT_PRIVACY이며 동의값은 true여야 합니다. "
              + "PREFERENCE, PROFILE_IMAGE는 선택 약관입니다. "
              + "프로필 이미지는 가입 후 별도 API에서 등록합니다.",
      requestBody =
          @RequestBody(
              required = true,
              description = "가입 방식별 인증 정보, 닉네임 및 약관 동의 여부",
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema = @Schema(implementation = SignupRequest.class),
                      examples = {
                        @ExampleObject(
                            name = "LOCAL 회원가입",
                            value =
                                """
                                                  {
                                                    "provider": "LOCAL",
                                                    "email": "user@example.com",
                                                    "emailVerificationToken": "example-verification-token",
                                                    "password": "ExamplePassword123!",
                                                    "nickname": "유저",
                                                    "agreements": {
                                                      "TOS": true,
                                                      "ACCOUNT_PRIVACY": true,
                                                      "PREFERENCE": false,
                                                      "PROFILE_IMAGE": false
                                                    }
                                                  }
                                                  """),
                        @ExampleObject(
                            name = "GOOGLE 회원가입",
                            value =
                                """
                                                  {
                                                    "provider": "GOOGLE",
                                                    "signupToken": "example-signup-token",
                                                    "nickname": "유저",
                                                    "agreements": {
                                                      "TOS": true,
                                                      "ACCOUNT_PRIVACY": true,
                                                      "PREFERENCE": false,
                                                      "PROFILE_IMAGE": false
                                                    }
                                                  }
                                                  """),
                        @ExampleObject(
                            name = "KAKAO 회원가입",
                            value =
                                """
                                              {
                                                "provider": "KAKAO",
                                                "signupToken": "example-kakao-signup-token",
                                                "nickname": "오늘이",
                                                "agreements": {
                                                  "TOS": true,
                                                  "ACCOUNT_PRIVACY": true,
                                                  "PREFERENCE": false,
                                                  "PROFILE_IMAGE": false
                                                }
                                              }
                                              """)
                      })))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "회원가입 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = {
                  @ExampleObject(
                      name = "LOCAL 회원가입 성공",
                      value =
                          """
                                        {
                                          "success": true,
                                          "data": {
                                            "memberId": "123e4567-e89b-12d3-a456-426614174000",
                                            "email": "user@example.com",
                                            "nickname": "유저",
                                            "coupleConnected": false,
                                            "createdAt": "2026-10-11T09:00:00"
                                          }
                                        }
                                        """),
                  @ExampleObject(
                      name = "GOOGLE·KAKAO 회원가입 성공",
                      value =
                          """
                                        {
                                          "success": true,
                                          "data": {
                                            "memberId": "123e4567-e89b-12d3-a456-426614174000",
                                            "email": null,
                                            "nickname": "오늘이",
                                            "coupleConnected": false,
                                            "createdAt": "2026-10-11T09:00:00"
                                          }
                                        }
                                        """)
                })),
    @ApiResponse(
        responseCode = "400",
        description = "필수 입력, 약관 동의, 이메일 인증 또는 소셜 가입 토큰 오류",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_TOKEN_INVALID\",\"message\":\"이메일 인증 정보가 유효하지 않습니다.\"}"),
                  @ExampleObject(
                      name = "Kakao 가입 토큰 오류",
                      value =
                          """
                                        {
                                          "success": false,
                                          "code": "KAKAO_SIGNUP_TOKEN_INVALID",
                                          "message": "Kakao 회원가입 인증 정보가 만료되었거나 유효하지 않습니다."
                                        }
                                        """)
                })),
    @ApiResponse(
        responseCode = "409",
        description = "이메일, 닉네임 또는 소셜 계정 중복",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "이메일 중복",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_ALREADY_EXISTS\",\"message\":\"이미 사용 중인 이메일입니다.\"}"),
                  @ExampleObject(
                      name = "닉네임 중복",
                      value =
                          "{\"success\":false,\"code\":\"NICKNAME_ALREADY_EXISTS\",\"message\":\"이미 사용 중인 닉네임입니다.\"}"),
                  @ExampleObject(
                      name = "Google 계정 중복",
                      value =
                          "{\"success\":false,\"code\":\"GOOGLE_ACCOUNT_ALREADY_EXISTS\",\"message\":\"이미 가입된 Google 계정입니다.\"}"),
                  @ExampleObject(
                      name = "Kakao 계정 중복",
                      value =
                          """
                                        {
                                          "success": false,
                                          "code": "KAKAO_ACCOUNT_ALREADY_EXISTS",
                                          "message": "이미 가입된 Kakao 계정입니다."
                                        }
                                        """)
                }))
  })
  ResponseEntity<CommonResponse<SignupResponse>> signup(SignupRequest request);

  /**
   * 이메일 인증번호를 발급하고 발송합니다.
   *
   * @param request 인증번호 발급 요청
   * @return 인증번호 유효 시간과 재발송 대기 시간
   */
  @Operation(
      summary = "이메일 인증번호 발송",
      description = "SIGNUP 목적으로 6자리 인증번호를 발송합니다. 인증번호는 30분 동안 유효하며 재발송은 1분 후 가능합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "인증번호 발송 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\",\"expiresIn\":1800,\"resendAvailableIn\":60}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "요청값 오류, 재발송 대기 시간 미경과 또는 발송 횟수 초과",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "1분 재발송 제한",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_RESEND_TOO_SOON\",\"message\":\"인증번호는 1분 후 다시 요청할 수 있습니다.\"}"),
                  @ExampleObject(
                      name = "30분 발송 제한",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_SEND_LIMIT_EXCEEDED\",\"message\":\"이메일 인증 요청 횟수를 초과했습니다. 30분 후 다시 시도해 주세요.\"}")
                })),
    @ApiResponse(
        responseCode = "500",
        description = "인증 이메일 발송 실패",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"EMAIL_DELIVERY_FAILED\",\"message\":\"인증 이메일 발송에 실패했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<EmailVerificationResponse>> issueEmailVerification(
      EmailVerificationRequest request);

  /**
   * 이메일 인증번호를 검증하고 완료 토큰을 발급합니다.
   *
   * @param request 인증번호 확인 요청
   * @return 인증 완료 토큰과 유효 시간
   */
  @Operation(
      summary = "이메일 인증번호 확인",
      description = "인증번호 확인 성공 시 회원가입에 사용할 일회성 인증 완료 토큰을 발급합니다. 3회 실패 시 새 인증번호가 이메일로 재발급됩니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "인증 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"verificationToken\":\"example-verification-token\",\"expiresIn\":1800}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "인증번호 오류, 만료 또는 필수 입력 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "인증번호 오류",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_CODE_INVALID\",\"message\":\"인증번호가 올바르지 않습니다.\",\"failureCount\":1,\"maxFailureCount\":3}"),
                  @ExampleObject(
                      name = "인증번호 만료",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_CODE_EXPIRED\",\"message\":\"인증번호가 만료되었거나 존재하지 않습니다.\"}"),
                  @ExampleObject(
                      name = "인증번호 재발급 제한",
                      value =
                          "{\"success\":false,\"code\":\"EMAIL_VERIFICATION_SEND_LIMIT_EXCEEDED\",\"message\":\"이메일 인증 요청 횟수를 초과했습니다. 30분 후 다시 시도해 주세요.\"}")
                })),
    @ApiResponse(
        responseCode = "500",
        description = "인증번호 자동 재발급 중 이메일 발송 실패",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"EMAIL_DELIVERY_FAILED\",\"message\":\"인증 이메일 발송에 실패했습니다.\"}")))
  })
  ResponseEntity<CommonResponse<EmailVerificationConfirmResponse>> confirmEmailVerification(
      EmailVerificationConfirmRequest request);

  /**
   * Refresh Token을 검증하고 새로운 인증 토큰을 발급합니다.
   *
   * <p>유효한 Refresh Token으로 요청하면 새로운 Access Token과 Refresh Token을 발급합니다. 기존 Refresh Token은 즉시 무효화되며,
   * 로그인 세션 ID는 유지됩니다.
   *
   * <p>클라이언트는 갱신 응답으로 받은 새 Refresh Token을 저장하고 이후 토큰 갱신 요청에 사용해야 합니다.
   *
   * @param request 기존 Refresh Token이 포함된 토큰 갱신 요청
   * @return 새 Access Token, 새 Refresh Token 및 Access Token 유효 시간
   */
  @Operation(
      summary = "토큰 갱신",
      description =
          "유효한 Refresh Token을 확인하고 새 Access Token과 Refresh Token을 발급합니다. "
              + "기존 Refresh Token은 즉시 무효화되며, 로그인 세션은 유지됩니다. "
              + "이후 갱신 요청에는 새로 발급된 Refresh Token을 사용해야 합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "토큰 갱신 및 Refresh Token 회전 성공",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":true,\"data\":{\"accessToken\":\"eyJ...\",\"refreshToken\":\"new-refresh-token\",\"expiresIn\":3600}}"))),
    @ApiResponse(
        responseCode = "400",
        description = "Refresh Token 누락",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"Refresh Token은 필수입니다.\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = "Refresh Token이 유효하지 않거나 이미 사용된 경우",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"success\":false,\"code\":\"REFRESH_TOKEN_INVALID\",\"message\":\"Refresh Token이 유효하지 않습니다.\"}")))
  })
  ResponseEntity<CommonResponse<TokenRefreshResponse>> refresh(TokenRefreshRequest request);
}
