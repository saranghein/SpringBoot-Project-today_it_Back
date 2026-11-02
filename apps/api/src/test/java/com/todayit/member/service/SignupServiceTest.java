package com.todayit.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.common.exception.BusinessException;
import com.todayit.common.exception.InvalidRequestException;
import com.todayit.member.client.external.GoogleIdentity;
import com.todayit.member.client.external.KakaoIdentity;
import com.todayit.member.entity.Agreement;
import com.todayit.member.entity.BlocklistMatchType;
import com.todayit.member.entity.EmailVerificationPurpose;
import com.todayit.member.entity.Member;
import com.todayit.member.entity.MemberAgreement;
import com.todayit.member.entity.MemberProvider;
import com.todayit.member.entity.MemberRole;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.AgreementRepository;
import com.todayit.member.repository.MemberAgreementRepository;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.repository.MemberRoleRepository;
import com.todayit.member.repository.NicknameBlocklistRepository;
import com.todayit.member.repository.PasswordBlocklistRepository;
import com.todayit.member.service.command.SignupCommand;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

  @Mock private MemberRepository memberRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private EmailVerificationService emailVerificationService;
  @Mock private MemberRoleRepository memberRoleRepository;
  @Mock private MemberAgreementRepository memberAgreementRepository;
  @Mock private PasswordBlocklistRepository passwordBlocklistRepository;
  @Mock private NicknameBlocklistRepository nicknameBlocklistRepository;
  @Mock private AgreementRepository agreementRepository;

  private SignupService signupService;

  private static final List<Agreement> CURRENT_AGREEMENTS =
      List.of(
          agreement(10, "TOS", true),
          agreement(20, "ACCOUNT_PRIVACY", true),
          agreement(30, "PREFERENCE", false),
          agreement(40, "PROFILE_IMAGE", false));

  @BeforeEach
  void setUp() {
    signupService =
        new SignupService(
            memberRepository,
            passwordEncoder,
            emailVerificationService,
            memberRoleRepository,
            memberAgreementRepository,
            passwordBlocklistRepository,
            nicknameBlocklistRepository,
            agreementRepository);
  }

  @Test
  @DisplayName("회원가입 시 약관 코드를 실제 agreement_id로 변환하여 저장한다")
  void createsLocalMemberWithAgreementCodes() {
    String email = "test@test.com";
    String password = "password123!";
    String verificationToken = "verification-token";

    Map<String, Boolean> agreements =
        Map.of(
            "TOS", true,
            "ACCOUNT_PRIVACY", true,
            "PREFERENCE", false,
            "PROFILE_IMAGE", false);

    SignupCommand command =
        new SignupCommand(email, verificationToken, password, "테스트", agreements);

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(passwordEncoder.encode(password)).thenReturn("encoded-password");
    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(memberRoleRepository.findRoleIdByName("USER")).thenReturn(Optional.of(1));

    Member savedMember = signupService.createLocalMember(command);

    assertThat(savedMember.getPassword()).isEqualTo("encoded-password");
    assertThat(savedMember.getCreatedAt()).isNotNull();

    verify(emailVerificationService)
        .validateVerificationToken(email, EmailVerificationPurpose.SIGNUP, verificationToken);
    verify(emailVerificationService).invalidateVerificationToken(verificationToken);
    verify(memberRoleRepository).save(any(MemberRole.class));

    ArgumentCaptor<MemberAgreement> captor = ArgumentCaptor.forClass(MemberAgreement.class);

    verify(memberAgreementRepository, times(4)).save(captor.capture());

    List<Integer> savedAgreementIds =
        captor.getAllValues().stream()
            .map(agreement -> (Integer) ReflectionTestUtils.getField(agreement, "agreementId"))
            .toList();

    assertThat(savedAgreementIds).containsExactlyInAnyOrder(10, 20, 30, 40);
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 회원가입을 거부한다")
  void rejectsSignupWhenLocalEmailAlreadyExists() {
    SignupCommand command =
        new SignupCommand(
            "test@test.com", "verification-token", "password123!", "테스트", Map.of("TOS", true));

    when(memberRepository.existsByEmailAndProvider("test@test.com", MemberProvider.LOCAL))
        .thenReturn(true);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(BusinessException.class)
        .hasMessage("이미 사용 중인 이메일입니다.");

    verify(emailVerificationService, never()).validateVerificationToken(any(), any(), any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("필수 약관에 동의하지 않으면 회원가입을 거부한다")
  void rejectsSignupWhenRequiredAgreementIsNotAccepted() {
    SignupCommand command =
        new SignupCommand(
            "test@test.com",
            "verification-token",
            "password123!",
            "테스트",
            Map.of(
                "TOS", false,
                "ACCOUNT_PRIVACY", true,
                "PREFERENCE", false,
                "PROFILE_IMAGE", false));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("필수 약관과 동의 항목을 확인해 주세요.");

    verify(emailVerificationService, never()).validateVerificationToken(any(), any(), any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("필수 약관 정보가 없으면 회원가입을 진행하지 않는다")
  void rejectsSignupWhenRequiredAgreementInformationDoesNotExist() {
    SignupCommand command =
        new SignupCommand(
            "test@test.com", "verification-token", "password123!", "테스트", Map.of("TOS", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(List.of());

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("필수 약관 정보가 없습니다.");

    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("존재하지 않는 약관 코드를 전달하면 회원가입을 거부한다")
  void rejectsSignupWhenAgreementCodeIsUnknown() {
    SignupCommand command =
        new SignupCommand(
            "test@test.com",
            "verification-token",
            "password123!",
            "테스트",
            Map.of(
                "TOS", true,
                "marketing", false));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("유효하지 않은 약관 코드가 포함되어 있습니다.");

    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("선택 약관을 전달하지 않아도 회원가입할 수 있다")
  void allowsSignupWithoutOptionalAgreements() {
    SignupCommand command =
        new SignupCommand(
            "test@test.com",
            "verification-token",
            "password123!",
            "테스트",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(passwordEncoder.encode("password123!")).thenReturn("encoded-password");
    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(memberRoleRepository.findRoleIdByName("USER")).thenReturn(Optional.of(1));

    Member savedMember = signupService.createLocalMember(command);

    assertThat(savedMember).isNotNull();
    verify(memberAgreementRepository, times(2)).save(any(MemberAgreement.class));
  }

  @Test
  @DisplayName("금칙어 비밀번호이면 회원가입을 거부한다")
  void rejectsSignupWhenPasswordIsBlocked() {
    String password = "password123!";

    SignupCommand command =
        new SignupCommand(
            "test@test.com",
            "verification-token",
            password,
            "테스트",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(passwordBlocklistRepository.existsByWordAndMatchTypeAndEnabledTrue(
            password, BlocklistMatchType.EXACT))
        .thenReturn(true);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("쉽게 추측되거나 유출된 비밀번호입니다. 다른 비밀번호를 입력해 주세요.");

    verify(passwordEncoder, never()).encode(any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("금칙어 닉네임이면 회원가입을 거부한다")
  void rejectsSignupWhenNicknameIsBlocked() {
    String nickname = "관리자";

    SignupCommand command =
        new SignupCommand(
            "test@test.com",
            "verification-token",
            "safePassword123!",
            nickname,
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(nicknameBlocklistRepository.existsBlockedNickname(nickname)).thenReturn(true);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("사용할 수 없는 닉네임입니다.");

    verify(passwordEncoder, never()).encode(any());
    verify(memberRepository, never()).save(any());
  }

  /** 테스트에 사용할 Agreement 엔티티를 생성합니다. */
  private static Agreement agreement(Integer id, String code, boolean required) {
    Agreement agreement = BeanUtils.instantiateClass(Agreement.class);

    ReflectionTestUtils.setField(agreement, "id", id);
    ReflectionTestUtils.setField(agreement, "code", code);
    ReflectionTestUtils.setField(agreement, "required", required);

    return agreement;
  }

  @Test
  @DisplayName("회원가입 시 이미 사용 중인 닉네임이면 저장하지 않는다")
  void rejectsSignupWhenNicknameAlreadyExists() {
    String nickname = "더미사용자";

    SignupCommand command =
        new SignupCommand(
            "new@test.com",
            "verification-token",
            "safePassword123!",
            nickname,
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(memberRepository.existsByNormalizedNickname(nickname)).thenReturn(true);

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(BusinessException.class)
        .hasMessage("이미 사용 중인 닉네임입니다.");

    verify(memberRepository, never()).save(any(Member.class));
  }

  @Test
  @DisplayName("DB 닉네임 UNIQUE 제약 위반은 닉네임 중복 오류로 변환한다")
  void convertsNicknameUniqueViolationToBusinessException() {
    String nickname = "중복닉네임";

    SignupCommand command =
        new SignupCommand(
            "new@test.com",
            "verification-token",
            "safePassword123!",
            nickname,
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(passwordEncoder.encode("safePassword123!")).thenReturn("encoded-password");
    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ConstraintViolationException constraintException =
        new ConstraintViolationException(
            "닉네임 중복", new SQLException("duplicate key", "23505"), "uk_member_nickname_normalized");

    doThrow(new DataIntegrityViolationException("DB UNIQUE 위반", constraintException))
        .when(memberRepository)
        .flush();

    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(BusinessException.class)
        .hasMessage("이미 사용 중인 닉네임입니다.");

    verify(memberRepository).flush();
    verify(memberRoleRepository, never()).save(any(MemberRole.class));
    verify(memberAgreementRepository, never()).save(any(MemberAgreement.class));
  }

  @Test
  @DisplayName("닉네임과 무관한 DB 제약 위반은 닉네임 중복 오류로 변환하지 않는다")
  void doesNotConvertUnrelatedConstraintViolation() {
    SignupCommand command =
        new SignupCommand(
            "new@test.com",
            "verification-token",
            "safePassword123!",
            "새닉네임",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);
    when(passwordEncoder.encode("safePassword123!")).thenReturn("encoded-password");
    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ConstraintViolationException constraintException =
        new ConstraintViolationException(
            "다른 DB 제약 위반",
            new SQLException("constraint violation", "23503"),
            "fk_member_preferred_region");

    DataIntegrityViolationException dbException =
        new DataIntegrityViolationException("DB 제약 위반", constraintException);

    doThrow(dbException).when(memberRepository).flush();

    assertThatThrownBy(() -> signupService.createLocalMember(command)).isSameAs(dbException);

    verify(memberRepository).flush();
    verify(memberRoleRepository, never()).save(any(MemberRole.class));
  }

  @Test
  @DisplayName("DB 이메일 UNIQUE 제약 위반은 이메일 중복 오류로 변환한다")
  void convertsEmailUniqueViolationToBusinessException() {
    // Given
    SignupCommand command =
        new SignupCommand(
            "duplicate@test.com",
            "verification-token",
            "safePassword123!",
            "새닉네임",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    when(passwordEncoder.encode("safePassword123!")).thenReturn("encoded-password");

    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ConstraintViolationException constraintException =
        new ConstraintViolationException(
            "이메일 중복", new SQLException("duplicate key", "23505"), "uk_member_email_provider");

    doThrow(new DataIntegrityViolationException("DB UNIQUE 위반", constraintException))
        .when(memberRepository)
        .flush();

    // When & Then
    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(BusinessException.class)
        .hasMessage("이미 사용 중인 이메일입니다.");

    verify(memberRepository).flush();
    verify(memberRoleRepository, never()).save(any(MemberRole.class));
    verify(memberAgreementRepository, never()).save(any(MemberAgreement.class));
    verify(emailVerificationService, never()).invalidateVerificationToken(any());
  }

  @Test
  @DisplayName("Google 회원가입 시 비밀번호 없이 회원과 권한 및 약관을 저장한다")
  void createsGoogleMemberWithAgreements() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-user-123", null, false);

    SignupCommand command =
        new SignupCommand(
            MemberProvider.GOOGLE,
            null,
            null,
            null,
            "signup-token",
            "구글회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(memberRoleRepository.findRoleIdByName("USER")).thenReturn(Optional.of(1));

    // When
    Member member = signupService.createGoogleMember(command, identity);

    // Then
    assertThat(member.getProvider()).isEqualTo(MemberProvider.GOOGLE);
    assertThat(member.getEmail()).isNull();
    assertThat(member.getPassword()).isNull();
    assertThat(member.getProviderUserId()).isEqualTo("google-user-123");
    assertThat(member.getNickname()).isEqualTo("구글회원");

    verify(memberRepository).save(any(Member.class));
    verify(memberRepository).flush();
    verify(memberRoleRepository).save(any(MemberRole.class));
    verify(memberAgreementRepository, times(2)).save(any(MemberAgreement.class));

    // Google 회원가입은 이메일 인증과 비밀번호 암호화를 수행하지 않음
    verify(passwordEncoder, never()).encode(any());
    verify(emailVerificationService, never()).validateVerificationToken(any(), any(), any());
  }

  @Test
  @DisplayName("이미 가입된 Google 고유 ID로는 중복 가입할 수 없다")
  void rejectsDuplicateGoogleAccount() {
    // Given
    GoogleIdentity identity = new GoogleIdentity("google-user-123", "google@test.com", true);

    SignupCommand command =
        new SignupCommand(
            MemberProvider.GOOGLE,
            null,
            null,
            null,
            "signup-token",
            "구글회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    // 같은 Google 계정의 회원이 이미 DB에 존재함
    Member existingMember = Member.createGoogle("google-user-123", "기존회원");

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.GOOGLE, "google-user-123"))
        .thenReturn(Optional.of(existingMember));

    // When & Then
    assertThatThrownBy(() -> signupService.createGoogleMember(command, identity))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.GOOGLE_ACCOUNT_ALREADY_EXISTS));

    // 중복 계정인 경우 새 회원을 저장하지 않음
    verify(memberRepository, never()).save(any());
  }

  @Test
  @DisplayName("서비스 직접 호출 시에도 비밀번호 필수 문자 조합을 검사한다")
  void rejectsPasswordWithoutRequiredCharacters() {

    // Given
    SignupCommand command =
        new SignupCommand(
            "test@test.com", "verification-token", "abcdefghij", "오늘이", Map.of("TOS", true));

    // When & Then
    assertThatThrownBy(() -> signupService.createLocalMember(command))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("비밀번호는 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.");

    // 유효하지 않은 비밀번호는 DB에 저장하지 않음
    verify(memberRepository, never()).save(any(Member.class));
  }

  @Test
  @DisplayName("닉네임 중복 확인 시 앞뒤 공백을 제거한다")
  void normalizesNicknameBeforeAvailabilityCheck() {

    // When
    boolean available = signupService.isNicknameAvailable("  오늘이  ");

    // Then
    assertThat(available).isTrue();

    // 공백 제거된 값으로 금칙어를 검사했는지 확인
    verify(nicknameBlocklistRepository).existsBlockedNickname("오늘이");

    // 공백 제거된 값으로 중복을 검사했는지 확인
    verify(memberRepository).existsByNormalizedNickname("오늘이");
  }

  @Test
  @DisplayName("Google 고유 ID UNIQUE 위반은 계정 중복 오류로 변환한다")
  void convertsGoogleUserIdUniqueViolationToBusinessException() {

    // Given
    GoogleIdentity identity = new GoogleIdentity("google-user-123", "google@test.com", true);

    SignupCommand command =
        new SignupCommand(
            MemberProvider.GOOGLE,
            null,
            null,
            null,
            "signup-token",
            "구글회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // 동시 가입 시 DB에서 발생할 수 있는 UNIQUE 제약 위반 재현
    ConstraintViolationException constraintException =
        new ConstraintViolationException(
            "Google 계정 고유 ID 중복",
            new SQLException("duplicate key", "23505"),
            "uk_member_provider_user_id");

    doThrow(new DataIntegrityViolationException("DB UNIQUE 위반", constraintException))
        .when(memberRepository)
        .flush();

    // When & Then
    assertThatThrownBy(() -> signupService.createGoogleMember(command, identity))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.GOOGLE_ACCOUNT_ALREADY_EXISTS));

    // 회원 저장 실패 후에는 권한과 약관 동의를 저장하지 않음
    verify(memberRepository).flush();

    verify(memberRoleRepository, never()).save(any(MemberRole.class));

    verify(memberAgreementRepository, never()).save(any(MemberAgreement.class));
  }

  @Test
  @DisplayName("이메일 없는 Kakao 회원을 권한 및 약관과 함께 저장한다")
  void createsKakaoMemberWithoutEmail() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    SignupCommand command =
        new SignupCommand(
            MemberProvider.KAKAO,
            null,
            null,
            null,
            "signup-token",
            "카카오회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    when(agreementRepository.findByDeletedFalse()).thenReturn(CURRENT_AGREEMENTS);

    when(memberRepository.save(any(Member.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(memberRoleRepository.findRoleIdByName("USER")).thenReturn(Optional.of(1));

    // When
    Member member = signupService.createKakaoMember(command, identity);

    // Then
    assertThat(member.getProvider()).isEqualTo(MemberProvider.KAKAO);
    assertThat(member.getEmail()).isNull();
    assertThat(member.getPassword()).isNull();
    assertThat(member.getProviderUserId()).isEqualTo("123456789");

    verify(memberRepository).save(any(Member.class));
    verify(memberRepository).flush();

    verify(memberRoleRepository).save(any(MemberRole.class));
    verify(memberAgreementRepository, times(2)).save(any(MemberAgreement.class));

    // Kakao 회원가입에서는 자체 이메일 인증과 비밀번호 암호화 불필요
    verify(passwordEncoder, never()).encode(any());
    verify(emailVerificationService, never()).validateVerificationToken(any(), any(), any());
  }

  @Test
  @DisplayName("이미 가입된 Kakao 사용자 ID는 중복 가입을 거부한다")
  void rejectsDuplicateKakaoAccount() {
    // Given
    KakaoIdentity identity = new KakaoIdentity("123456789", null, false);

    SignupCommand command =
        new SignupCommand(
            MemberProvider.KAKAO,
            null,
            null,
            null,
            "signup-token",
            "카카오회원",
            Map.of(
                "TOS", true,
                "ACCOUNT_PRIVACY", true));

    Member existingMember = Member.createKakao("123456789", "기존회원");

    when(memberRepository.findByProviderAndProviderUserId(MemberProvider.KAKAO, "123456789"))
        .thenReturn(Optional.of(existingMember));

    // When & Then
    assertThatThrownBy(() -> signupService.createKakaoMember(command, identity))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getErrorCode())
                    .isEqualTo(MemberErrorCode.KAKAO_ACCOUNT_ALREADY_EXISTS));

    verify(memberRepository, never()).save(any(Member.class));
  }
}
