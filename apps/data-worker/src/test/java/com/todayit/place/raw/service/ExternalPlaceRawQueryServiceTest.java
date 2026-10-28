package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.raw.entity.ExternalPlaceRaw;
import com.todayit.place.raw.repository.ExternalPlaceRawRepository;
import com.todayit.place.raw.service.model.RawRetryTarget;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

/** 재수집 후보의 공급원·상태·건수 제한과 스냅샷 반환을 검증합니다. */
class ExternalPlaceRawQueryServiceTest {

  private final ExternalPlaceRawRepository repository = mock(ExternalPlaceRawRepository.class);
  private final ExternalPlaceRawQueryService service = new ExternalPlaceRawQueryService(repository);

  /** 잘못된 ID 구간을 DB 접근 전에 거부하는지 확인합니다. */
  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"-1,10,2", "11,10,2", "0,10,0", "0,10,11"})
  void rejectsInvalidKeysetRange(long afterId, long upperBound, int limit) {
    assertThatThrownBy(() -> service.findSucceededAfter("TOUR_API", afterId, upperBound, limit))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }

  /** 공급원 실패 행의 첫 페이지를 제한 조회하고 불변 스냅샷으로 반환하는지 검증합니다. */
  @Test
  void selectsOnlyFailedProviderRowsWithBoundedFirstPageAndReturnsSnapshot() {
    var raw = mock(ExternalPlaceRaw.class);
    when(raw.getSourceId()).thenReturn("001");
    when(raw.getPayload()).thenReturn("{\"list\":{}}");
    when(repository.findByProviderAndStatusOrderByIdAsc("TOUR_API", "FAILED", PageRequest.of(0, 2)))
        .thenReturn(List.of(raw));

    var targets = service.findFailed("TOUR_API", 2);

    assertThat(targets).containsExactly(new RawRetryTarget("001", "{\"list\":{}}"));
    assertThatThrownBy(() -> targets.clear()).isInstanceOf(UnsupportedOperationException.class);
    verify(repository)
        .findByProviderAndStatusOrderByIdAsc("TOUR_API", "FAILED", PageRequest.of(0, 2));
  }

  /**
   * 유효하지 않은 조회 건수를 DB 접근 전에 거부하는지 검증합니다.
   *
   * @param limit 검증할 최대 처리 건수
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 11})
  void rejectsInvalidLimitBeforeDatabaseAccess(int limit) {
    assertThatThrownBy(() -> service.findFailed("TOUR_API", limit))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }
}
