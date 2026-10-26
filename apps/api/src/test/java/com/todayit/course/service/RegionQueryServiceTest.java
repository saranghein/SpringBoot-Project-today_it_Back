package com.todayit.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.course.exception.InvalidRegionPathException;
import com.todayit.course.repository.RegionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegionQueryServiceTest {
  @Mock private RegionRepository regionRepository;

  @ParameterizedTest
  @CsvSource({"41,", ",115", "41,115", ","})
  void findsRegionsByProvidedCodes(String sidoCode, String sigunguCode) {
    when(regionRepository.findIdsByCodes(sidoCode, sigunguCode)).thenReturn(List.of(1, 2));
    RegionQueryService service = new RegionQueryService(regionRepository);

    assertThat(service.findRegionIds(sidoCode, sigunguCode)).containsExactly(1, 2);
  }

  @ParameterizedTest
  @CsvSource({"1,", "411,", "ab,", "' ',", "'',", ",11", ",1100", ",abc", ",' '", ",''"})
  void rejectsMalformedCodesBeforeQuerying(String sidoCode, String sigunguCode) {
    RegionQueryService service = new RegionQueryService(regionRepository);

    assertThatThrownBy(() -> service.findRegionIds(sidoCode, sigunguCode))
        .isInstanceOf(InvalidRegionPathException.class);
    verifyNoInteractions(regionRepository);
  }

  @Test
  void rejectsUnknownRegion() {
    when(regionRepository.findIdsByCodes("41", "999")).thenReturn(List.of());

    assertThatThrownBy(() -> new RegionQueryService(regionRepository).findRegionIds("41", "999"))
        .isInstanceOf(InvalidRegionPathException.class);
  }
}
