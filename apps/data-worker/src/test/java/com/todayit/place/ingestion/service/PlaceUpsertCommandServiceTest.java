package com.todayit.place.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.ingestion.repository.PlaceSourceRepository;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import com.todayit.place.service.model.NormalizedPlace;
import com.todayit.place.service.model.NormalizedPlace.Category;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 정제·지역 조회를 통과한 장소만 저장 계약에 전달되는지 검증합니다. */
class PlaceUpsertCommandServiceTest {

  private final PlaceRegionQueryService regions = mock(PlaceRegionQueryService.class);
  private final PlaceSourceRepository repository = mock(PlaceSourceRepository.class);
  private final PlaceUpsertCommandService service =
      new PlaceUpsertCommandService(regions, repository, mock(PlaceIngestionFailureService.class));

  /** 명시적으로 활성화하지 않으면 장소 저장 기능이 등록되지 않는지 검증합니다. */
  @Test
  void upsertIsDisabledUnlessExplicitlyEnabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertCommandService.class)
        .run(context -> assertThat(context).doesNotHaveBean(PlaceUpsertCommandService.class));
  }

  /** 실제 조회·저장 구현 없이 활성화하면 대체 구현 없이 시작이 실패하는지 검증합니다. */
  @Test
  void enablingWithoutRealAdaptersFailsInsteadOfRegisteringFakeRegionOrStorage() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertCommandService.class)
        .withPropertyValues("todayit.place.upsert.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  /** 정상 정제 결과가 아니면 지역 조회와 저장을 건너뛰는지 검증합니다. */
  @Test
  void unnormalizedPlaceNeverReadsRegionOrWritesAnything() {
    var result =
        service.upsert(
            new PlaceNormalizationResult(
                1,
                "001",
                Status.REVIEW_REQUIRED,
                PlaceNormalizationResult.Reason.CATEGORY_UNMAPPED,
                null));

    assertThat(result.status()).isEqualTo(PlaceUpsertResult.Status.NORMALIZATION_SKIPPED);
    verifyNoInteractions(regions, repository);
  }

  /** 지역이 없으면 장소나 지역을 생성하지 않고 보류하는지 검증합니다. */
  @Test
  void missingRegionDefersWithoutCreatingPlaceOrRegion() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.empty());

    assertThat(service.upsert(normalized()).status())
        .isEqualTo(PlaceUpsertResult.Status.REGION_NOT_FOUND);

    verifyNoInteractions(repository);
  }

  /** 공급원·원천 ID와 조회한 지역 ID를 원자적 저장 계약에 전달하는지 검증합니다. */
  @Test
  void passesProviderIdentityAndResolvedRegionToAtomicStorageContract() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(7));
    when(repository.upsert(anyString(), anyString(), anyInt(), any())).thenReturn(23);
    var input = normalized();

    var result = service.upsert(input);

    assertThat(result.placeId()).isEqualTo(23);
    assertThat(result.status()).isEqualTo(PlaceUpsertResult.Status.APPLIED);
    verify(repository).upsert("TOUR_API", "001", 7, input.place());
  }

  /** 양수가 아닌 지역 ID를 실제 저장 전에 거부하는지 검증합니다. */
  @Test
  void rejectsPlaceholderRegionIdBeforeStorage() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(0));

    assertThatThrownBy(() -> service.upsert(normalized()))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(repository);
  }

  /** 저장 오류를 적재 성공으로 보고하지 않는지 검증합니다. */
  @Test
  void storageFailureIsNotReportedAsApplied() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(7));
    when(repository.upsert(anyString(), anyString(), anyInt(), any()))
        .thenThrow(new IllegalStateException("fixture"));

    assertThatThrownBy(() -> service.upsert(normalized()))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * 저장 검증에 사용할 정상 정제 결과를 생성합니다.
   *
   * @return 장소와 원천 ID를 포함하는 정상 정제 결과
   */
  private PlaceNormalizationResult normalized() {
    return new PlaceNormalizationResult(
        1,
        "001",
        Status.NORMALIZED,
        null,
        new NormalizedPlace(
            "공원",
            "서울 종로구",
            new BigDecimal("37.5"),
            new BigDecimal("127.1"),
            Category.NATURE_WALK,
            "11",
            "110",
            "VE030100",
            null,
            List.of()));
  }
}
