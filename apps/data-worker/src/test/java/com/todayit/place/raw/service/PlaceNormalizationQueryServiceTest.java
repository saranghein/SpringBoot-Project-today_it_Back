package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 원본 변경 없이 성공 원본의 정제 결과와 사유를 조회하는지 검증합니다. */
class PlaceNormalizationQueryServiceTest {

  /** 성공 원본만 정제하여 사유를 반환하고 원본은 갱신하지 않는지 검증합니다. */
  @Test
  void previewsOnlySuccessfulSnapshotsAndReturnsReasonsWithoutUpdatingSource() {
    var query = mock(ExternalPlaceRawQueryService.class);
    var snapshot = new RawPlaceSnapshot(1, "TOUR_API", "001", "SUCCESS", "invalid-json");
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of(snapshot));
    var service = new PlaceNormalizationQueryService(query, new TourApiPlaceNormalizationService());

    var results = service.previewTourApi(10);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().status())
        .isEqualTo(
            com.todayit.place.raw.service.model.PlaceNormalizationResult.Status.REVIEW_REQUIRED);
    assertThat(snapshot.status()).isEqualTo("SUCCESS");
    assertThat(snapshot.payloadJson()).isEqualTo("invalid-json");
    verify(query).findSucceeded("TOUR_API", 10);
  }

  /** 성공 원본이 없으면 빈 정제 미리보기를 반환하는지 검증합니다. */
  @Test
  void noSuccessfulRowsReturnsEmptyPreview() {
    var query = mock(ExternalPlaceRawQueryService.class);
    when(query.findSucceeded("TOUR_API", 10)).thenReturn(List.of());

    var results =
        new PlaceNormalizationQueryService(query, new TourApiPlaceNormalizationService())
            .previewTourApi(10);

    assertThat(results).isEmpty();
  }
}
