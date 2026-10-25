package com.todayit.place.client.external;

import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 활성화된 데이터 원천을 식별자로 선택하며 중복 등록을 거부합니다. */
public final class PlaceDataSourceRegistry {

  private final Map<SourceKey, PlaceDataSource> sources;

  /**
   * 공급원 구현체를 등록합니다.
   *
   * @param sources 활성화된 구현체
   * @throws IllegalArgumentException 같은 식별자가 중복 등록된 경우
   */
  public PlaceDataSourceRegistry(List<PlaceDataSource> sources) {
    Map<SourceKey, PlaceDataSource> registered = new HashMap<>();
    for (PlaceDataSource source : sources) {
      if (registered.putIfAbsent(source.sourceKey(), source) != null) {
        throw new IllegalArgumentException("장소 데이터 원천이 중복 등록되었습니다.");
      }
    }
    this.sources = Map.copyOf(registered);
  }

  /**
   * 활성화된 구현체를 반환합니다.
   *
   * @param key 공급원·데이터셋 식별자
   * @return 수집 구현체
   * @throws PlaceSourceException 원천이 비활성 또는 미등록일 때
   */
  public PlaceDataSource get(SourceKey key) {
    PlaceDataSource source = sources.get(key);
    if (source == null) {
      throw new PlaceSourceException(PlaceSourceException.Reason.UNSUPPORTED);
    }
    return source;
  }
}
