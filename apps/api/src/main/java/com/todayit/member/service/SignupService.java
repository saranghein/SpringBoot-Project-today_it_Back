package com.todayit.member.service;

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
import com.todayit.member.exception.InvalidEmailVerificationTokenException;
import com.todayit.member.exception.MemberErrorCode;
import com.todayit.member.repository.AgreementRepository;
import com.todayit.member.repository.MemberAgreementRepository;
import com.todayit.member.repository.MemberRepository;
import com.todayit.member.repository.MemberRoleRepository;
import com.todayit.member.repository.NicknameBlocklistRepository;
import com.todayit.member.repository.PasswordBlocklistRepository;
import com.todayit.member.service.command.SignupCommand;
import com.todayit.member.util.EmailNormalizer;
import com.todayit.member.util.PasswordValidator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** LOCAL·GOOGLE·KAKAO 회원가입과 공통 회원 정보 저장을 처리합니다. */
@Service
public class SignupService {

  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailVerificationService emailVerificationService;
  private final MemberRoleRepository memberRoleRepository;
  private final MemberAgreementRepository memberAgreementRepository;
  private final PasswordBlocklistRepository passwordBlocklistRepository;
  private final NicknameBlocklistRepository nicknameBlocklistRepository;
  private final AgreementRepository agreementRepository;

  private static final String DEFAULT_ROLE_NAME = "USER";
  private static final Logger LOG = LoggerFactory.getLogger(SignupService.class);

  /**
   * 회원가입에 필요한 저장소와 서비스를 받습니다.
   *
   * @param memberRepository 회원 Repository
   * @param passwordEncoder 비밀번호 암호화
   * @param emailVerificationService 이메일 인증 Service
   * @param memberRoleRepository 회원 권한 Repository
   * @param memberAgreementRepository 회원 약관 동의 Repository
   * @param passwordBlocklistRepository 비밀번호 금칙어 Repository
   * @param nicknameBlocklistRepository 닉네임 금칙어 Repository
   * @param agreementRepository 약관 Repository
   */
  public SignupService(
      MemberRepository memberRepository,
      PasswordEncoder passwordEncoder,
      EmailVerificationService emailVerificationService,
      MemberRoleRepository memberRoleRepository,
      MemberAgreementRepository memberAgreementRepository,
      PasswordBlocklistRepository passwordBlocklistRepository,
      NicknameBlocklistRepository nicknameBlocklistRepository,
      AgreementRepository agreementRepository) {

    this.memberRepository = memberRepository;
    this.passwordEncoder = passwordEncoder;
    this.emailVerificationService = emailVerificationService;
    this.memberRoleRepository = memberRoleRepository;
    this.memberAgreementRepository = memberAgreementRepository;
    this.passwordBlocklistRepository = passwordBlocklistRepository;
    this.nicknameBlocklistRepository = nicknameBlocklistRepository;
    this.agreementRepository = agreementRepository;
  }

  /**
   * LOCAL 회원가입에 사용할 이메일의 중복 여부를 확인합니다.
   *
   * @param email 회원 이메일
   * @return 사용 가능하면 true
   */
  @Transactional(readOnly = true)
  public boolean isEmailAvailable(String email) {
    String normalizedEmail = EmailNormalizer.normalize(email);

    return !memberRepository.existsByEmailAndProvider(normalizedEmail, MemberProvider.LOCAL);
  }

  /**
   * 닉네임 형식과 금칙어, 중복 여부를 확인합니다.
   *
   * @param nickname 검사할 닉네임
   * @return 사용 가능한 닉네임이면 true
   * @throws InvalidRequestException 닉네임 형식이 잘못되었거나 금칙어인 경우
   */
  @Transactional(readOnly = true)
  public boolean isNicknameAvailable(String nickname) {
    // 닉네임 필수 입력 확인
    if (nickname == null || nickname.isBlank()) {
      throw new InvalidRequestException("닉네임은 필수입니다.");
    }

    // 앞뒤 공백을 제거한 뒤 검증
    String normalizedNickname = nickname.strip();

    // 닉네임 길이 확인
    if (normalizedNickname.length() < 2 || normalizedNickname.length() > 8) {
      throw new InvalidRequestException("닉네임은 2자 이상 8자 이하이어야 합니다.");
    }

    // 한글, 영문, 숫자 외 문자와 중간 공백은 거부
    if (!normalizedNickname.matches("[가-힣A-Za-z0-9]+")) {
      throw new InvalidRequestException("닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.");
    }

    // 정규화된 닉네임으로 금칙어 검사
    if (nicknameBlocklistRepository.existsBlockedNickname(normalizedNickname)) {
      throw new InvalidRequestException("사용할 수 없는 닉네임입니다.");
    }

    // 정규화된 닉네임으로 중복 확인
    return !memberRepository.existsByNormalizedNickname(normalizedNickname);
  }

  /**
   * 이메일 인증을 확인하고 LOCAL 회원을 생성합니다.
   *
   * @param command LOCAL 회원가입 정보
   * @return 생성된 회원
   * @throws BusinessException 이메일 또는 닉네임이 중복된 경우
   * @throws InvalidRequestException 약관 또는 비밀번호가 유효하지 않은 경우
   * @throws InvalidEmailVerificationTokenException 이메일 인증에 실패한 경우
   */
  @Transactional
  public Member createLocalMember(SignupCommand command) {
    // HTTP 요청을 거치지 않아도 비밀번호 정책을 검사
    PasswordValidator.validate(command.password());

    String normalizedEmail = EmailNormalizer.normalize(command.email());

    // 동일한 LOCAL 이메일로 이미 가입했는지 검사
    if (memberRepository.existsByEmailAndProvider(normalizedEmail, MemberProvider.LOCAL)) {
      throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
    }

    // 현재 적용 중인 약관을 조회하고 필수 동의 여부 검증
    Map<String, Agreement> agreementsByCode = validateAgreements(command.agreements());

    // LOCAL 회원은 이메일 인증 완료 토큰이 반드시 필요
    emailVerificationService.validateVerificationToken(
        normalizedEmail, EmailVerificationPurpose.SIGNUP, command.emailVerificationToken());

    // 금칙어로 등록된 비밀번호는 사용할 수 없음
    if (passwordBlocklistRepository.existsByWordAndMatchTypeAndEnabledTrue(
        command.password(), BlocklistMatchType.EXACT)) {
      throw new InvalidRequestException("쉽게 추측되거나 유출된 비밀번호입니다. 다른 비밀번호를 입력해 주세요.");
    }

    // 닉네임은 가입 직전에 다시 확인
    validateNicknameAvailability(command.nickname());

    // LOCAL 회원만 비밀번호를 암호화해 저장
    String encodedPassword = passwordEncoder.encode(command.password());

    Member member = Member.createLocal(normalizedEmail, encodedPassword, command.nickname());

    // 회원, USER 권한, 약관 동의를 하나의 DB 트랜잭션으로 저장
    Member savedMember = saveMemberWithAgreements(member, command.agreements(), agreementsByCode);

    // DB 커밋 성공 후 이메일 인증 토큰을 무효화하도록 등록
    invalidateVerificationTokenAfterCommit(command.emailVerificationToken());

    return savedMember;
  }

  /**
   * 검증된 Google 계정 정보로 GOOGLE 회원을 생성합니다.
   *
   * <p>상위 Facade에서 Redis 가입 토큰을 검증하고 Google 인증 정보를 조회한 뒤 전달합니다. 회원, 권한, 약관 동의 정보는 하나의 DB 트랜잭션으로
   * 저장합니다.
   *
   * @param command GOOGLE 회원가입 정보
   * @param identity Google에서 인증된 계정 정보
   * @return 생성된 Google 회원
   * @throws BusinessException 인증 정보가 유효하지 않거나 이미 가입된 계정인 경우
   * @throws InvalidRequestException 닉네임이나 약관 동의가 유효하지 않은 경우
   */
  @Transactional
  public Member createGoogleMember(SignupCommand command, GoogleIdentity identity) {

    // Redis에서 복원된 Google 사용자 고유 ID 검증
    if (identity == null
        || identity.providerUserId() == null
        || identity.providerUserId().isBlank()
        || identity.providerUserId().length() > 255) {
      throw new BusinessException(MemberErrorCode.GOOGLE_SIGNUP_TOKEN_INVALID);
    }

    // Google 고유 ID(sub)가 이미 가입된 계정에 연결되어 있는지 검사
    boolean alreadyRegistered =
        memberRepository
            .findByProviderAndProviderUserId(MemberProvider.GOOGLE, identity.providerUserId())
            .isPresent();

    if (alreadyRegistered) {
      throw new BusinessException(MemberErrorCode.GOOGLE_ACCOUNT_ALREADY_EXISTS);
    }

    // LOCAL 회원가입과 동일한 약관 검증 로직 사용
    Map<String, Agreement> agreementsByCode = validateAgreements(command.agreements());

    // LOCAL과 동일한 닉네임 형식, 금칙어 및 중복 기준 적용
    validateNicknameAvailability(command.nickname());

    // Google 회원은 이메일 없이 고유 ID로 식별
    Member member = Member.createGoogle(identity.providerUserId(), command.nickname());

    // 회원, USER 권한, 약관 동의를 동일한 방식으로 저장
    return saveMemberWithAgreements(member, command.agreements(), agreementsByCode);
  }

  /**
   * 검증된 Kakao 계정 정보로 KAKAO 회원을 생성합니다.
   *
   * <p>상위 Facade에서 Redis 임시 가입 토큰을 검증한 뒤 호출합니다. 이메일은 저장하지 않으며 회원과 기본 권한 및 약관 동의를 하나의 DB 트랜잭션으로
   * 저장합니다.
   *
   * @param command KAKAO 회원가입 정보
   * @param identity Kakao에서 인증된 계정 정보
   * @return 생성된 Kakao 회원
   * @throws BusinessException 인증 정보가 유효하지 않거나 이미 가입된 계정인 경우
   * @throws InvalidRequestException 닉네임이나 약관 동의가 유효하지 않은 경우
   */
  @Transactional
  public Member createKakaoMember(SignupCommand command, KakaoIdentity identity) {
    // Redis에서 복원한 Kakao 사용자 고유 ID 재검증
    if (identity == null
        || identity.providerUserId() == null
        || identity.providerUserId().isBlank()
        || identity.providerUserId().length() > 255) {
      throw new BusinessException(MemberErrorCode.KAKAO_SIGNUP_TOKEN_INVALID);
    }

    // Kakao 고유 ID로 중복 가입 여부 확인
    boolean alreadyRegistered =
        memberRepository
            .findByProviderAndProviderUserId(MemberProvider.KAKAO, identity.providerUserId())
            .isPresent();

    if (alreadyRegistered) {
      throw new BusinessException(MemberErrorCode.KAKAO_ACCOUNT_ALREADY_EXISTS);
    }

    // 기존 회원가입과 동일한 약관 검증
    Map<String, Agreement> agreementsByCode = validateAgreements(command.agreements());

    // 닉네임 형식, 금칙어 및 중복 확인
    validateNicknameAvailability(command.nickname());

    // Kakao 회원은 이메일 없이 고유 ID로 식별
    Member member = Member.createKakao(identity.providerUserId(), command.nickname());

    // 회원, USER 권한, 약관 동의를 하나의 트랜잭션으로 저장
    return saveMemberWithAgreements(member, command.agreements(), agreementsByCode);
  }

  /**
   * 가입 요청에 포함된 약관 동의를 현재 적용 중인 약관과 비교합니다.
   *
   * @param requestedAgreements 약관 코드별 동의 여부
   * @return 약관 코드를 Key로 하는 현재 약관 정보
   */
  private Map<String, Agreement> validateAgreements(Map<String, Boolean> requestedAgreements) {

    if (requestedAgreements == null || requestedAgreements.isEmpty()) {
      throw new InvalidRequestException("약관 동의 정보는 필수입니다.");
    }

    List<Agreement> currentAgreements = agreementRepository.findByDeletedFalse();

    // 적용 중인 필수 약관이 없는 경우 가입 처리 중단
    boolean hasRequiredAgreement = currentAgreements.stream().anyMatch(Agreement::isRequired);

    if (!hasRequiredAgreement) {
      throw new IllegalStateException("필수 약관 정보가 없습니다.");
    }

    Map<String, Agreement> agreementsByCode =
        currentAgreements.stream()
            .collect(Collectors.toMap(Agreement::getCode, agreement -> agreement));

    // 존재하지 않거나 현재 적용되지 않는 약관 코드는 거부
    boolean hasUnknownAgreement =
        requestedAgreements.keySet().stream().anyMatch(code -> !agreementsByCode.containsKey(code));

    if (hasUnknownAgreement) {
      throw new InvalidRequestException("유효하지 않은 약관 코드가 포함되어 있습니다.");
    }

    // null은 명시적인 동의 또는 미동의로 취급하지 않음
    if (requestedAgreements.values().stream().anyMatch(agreed -> agreed == null)) {
      throw new InvalidRequestException("약관 동의 여부를 확인해 주세요.");
    }

    // 필수 약관은 반드시 true여야 함
    boolean missingRequiredAgreement =
        currentAgreements.stream()
            .filter(Agreement::isRequired)
            .anyMatch(
                agreement -> !Boolean.TRUE.equals(requestedAgreements.get(agreement.getCode())));

    if (missingRequiredAgreement) {
      throw new InvalidRequestException("필수 약관과 동의 항목을 확인해 주세요.");
    }

    return agreementsByCode;
  }

  /**
   * 닉네임의 사용 가능 여부를 검사하고 중복이면 예외를 발생시킵니다.
   *
   * @param nickname 확인할 닉네임
   */
  private void validateNicknameAvailability(String nickname) {
    if (!isNicknameAvailable(nickname)) {
      throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
    }
  }

  /**
   * 회원과 기본 권한 및 약관 동의 정보를 저장합니다.
   *
   * <p>이 메서드는 LOCAL·GOOGLE·KAKAO 모두 사용하며 별도 public 트랜잭션 메서드 안에서 실행합니다.
   *
   * @param member 저장할 회원
   * @param requestedAgreements 약관 코드별 동의 여부
   * @param agreementsByCode 검증한 현재 약관 정보
   * @return 저장된 회원
   */
  private Member saveMemberWithAgreements(
      Member member,
      Map<String, Boolean> requestedAgreements,
      Map<String, Agreement> agreementsByCode) {

    Member savedMember;

    try {
      // INSERT를 즉시 실행해 UNIQUE 제약 위반을 확인
      savedMember = memberRepository.save(member);
      memberRepository.flush();

    } catch (DataIntegrityViolationException exception) {

      Throwable cause = exception;

      while (cause != null) {
        if (cause instanceof ConstraintViolationException constraintException) {
          String constraintName = constraintException.getConstraintName();

          if (constraintName != null) {

            // 닉네임 중복은 LOCAL과 GOOGLE에 동일하게 적용
            if ("uk_member_nickname_normalized".equalsIgnoreCase(constraintName)) {
              throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_EXISTS);
            }

            // 소셜 제공자별 사용자 고유 ID 중복 처리
            if ("uk_member_provider_user_id".equalsIgnoreCase(constraintName)) {
              if (member.getProvider() == MemberProvider.GOOGLE) {
                throw new BusinessException(MemberErrorCode.GOOGLE_ACCOUNT_ALREADY_EXISTS);
              }

              if (member.getProvider() == MemberProvider.KAKAO) {
                throw new BusinessException(MemberErrorCode.KAKAO_ACCOUNT_ALREADY_EXISTS);
              }
            }

            // LOCAL 회원 이메일 UNIQUE 제약 위반 처리
            if ("uk_member_email_provider".equalsIgnoreCase(constraintName)
                || "uk_member_email_provider_normalized".equalsIgnoreCase(constraintName)) {
              throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
            }
          }
        }

        cause = cause.getCause();
      }

      // 처리하지 않은 DB 제약 위반은 원래 예외 그대로 전파
      throw exception;
    }

    // 신규 회원에게 기본 USER 권한 부여
    Integer userRoleId =
        memberRoleRepository
            .findRoleIdByName(DEFAULT_ROLE_NAME)
            .orElseThrow(() -> new IllegalStateException("USER 권한 정보가 없습니다."));

    memberRoleRepository.save(MemberRole.create(savedMember.getId(), userRoleId));

    // 동의한 약관 코드와 실제 agreement_id를 연결해 저장
    requestedAgreements.forEach(
        (code, agreed) -> {
          Integer agreementId = agreementsByCode.get(code).getId();

          memberAgreementRepository.save(
              MemberAgreement.create(agreementId, savedMember.getId(), agreed));
        });

    return savedMember;
  }

  /**
   * DB 커밋 후 LOCAL 이메일 인증 토큰을 무효화합니다.
   *
   * @param verificationToken 이메일 인증 완료 토큰
   */
  private void invalidateVerificationTokenAfterCommit(String verificationToken) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      emailVerificationService.invalidateVerificationToken(verificationToken);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new EmailVerificationTokenCleanupService(emailVerificationService, verificationToken));
  }

  /** LOCAL 회원가입 커밋 후 이메일 인증 토큰을 정리합니다. */
  private static final class EmailVerificationTokenCleanupService
      implements TransactionSynchronization {

    private final EmailVerificationService emailVerificationService;
    private final String verificationToken;

    private EmailVerificationTokenCleanupService(
        EmailVerificationService emailVerificationService, String verificationToken) {
      this.emailVerificationService = emailVerificationService;
      this.verificationToken = verificationToken;
    }

    @Override
    public void afterCommit() {
      try {
        emailVerificationService.invalidateVerificationToken(verificationToken);
      } catch (DataAccessException exception) {
        LOG.error("회원가입 완료 후 이메일 인증 토큰 삭제에 실패했습니다.", exception);
      }
    }
  }
}
