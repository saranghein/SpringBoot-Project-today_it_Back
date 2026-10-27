package com.todayit.place.client.external.support;

import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceSourceException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 공급원 호출 결과를 원문·URL·인증 정보 없이 한 번 기록합니다. */
public final class PlaceSourceCallLogger {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceSourceCallLogger.class);

  private PlaceSourceCallLogger() {}

  /**
   * 공급원 수집을 실행하고 성공 건수 또는 실패 분류와 소요 시간을 기록합니다.
   *
   * @param <T> 수집 결과 타입
   * @param key 공급원·데이터셋 식별자
   * @param component 조회 구성 요소
   * @param operation PAGE 또는 COMPONENT 작업 구분
   * @param action 공급원 호출과 응답 검증
   * @return 수집 결과
   * @throws PlaceSourceException 공급원 호출 또는 응답 검증 실패 시
   */
  public static <T> T call(
      SourceKey key, Component component, Operation operation, Supplier<T> action) {
    long startedAt = System.nanoTime();
    LOG.debug(
        "place_source_start 외부 조회 시작 공급원={} 데이터셋={} 작업={} 항목={}",
        key.provider(),
        key.dataset(),
        operation,
        component);
    try {
      T result = action.get();
      long elapsedMs = elapsedMs(startedAt);
      if (result instanceof SourcePage page) {
        LOG.info(
            "place_source_success 외부 조회 성공 공급원={} 데이터셋={} 작업={} 항목={} 조회건수={} 전체건수={} 다음페이지={} 소요시간={}ms",
            key.provider(),
            key.dataset(),
            operation,
            component,
            page.items().size(),
            page.totalCount(),
            !page.nextCursor().isEmpty(),
            elapsedMs);
      } else {
        LOG.info(
            "place_source_success 외부 조회 성공 공급원={} 데이터셋={} 작업={} 항목={} 소요시간={}ms",
            key.provider(),
            key.dataset(),
            operation,
            component,
            elapsedMs);
      }
      return result;
    } catch (PlaceSourceException exception) {
      LOG.warn(
          "place_source_failure 외부 조회 실패 공급원={} 데이터셋={} 작업={} 항목={} 사유={} 소요시간={}ms",
          key.provider(),
          key.dataset(),
          operation,
          component,
          exception.getReason(),
          elapsedMs(startedAt));
      throw exception;
    }
  }

  /**
   * 시스템 시각 보정에 영향받지 않는 경과 시간을 밀리초로 반환합니다.
   *
   * @param startedAt System.nanoTime으로 측정한 시작 값
   * @return 시작 이후 경과 시간, 밀리초 미만은 버림
   */
  private static long elapsedMs(long startedAt) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
  }

  /** 수집 계약의 페이지 조회와 개별 구성 요소 조회를 구분합니다. */
  public enum Operation {
    PAGE,
    COMPONENT
  }
}
