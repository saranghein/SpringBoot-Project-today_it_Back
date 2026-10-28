package com.todayit.place.raw.service;

import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.RawCollectionResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 지역·관광타입별 수집을 설정 순서대로 조정하고 결과를 합산합니다. */
@Service
@ConditionalOnExpression(
    "${todayit.place.collection.enabled:false} or ${todayit.place.schedule.enabled:false}")
public class TourApiCollectionPlanService {

  private static final Logger LOG = LoggerFactory.getLogger(TourApiCollectionPlanService.class);
  private final TourApiRawCollectionService collection;

  /**
   * 개별 범위의 수집 서비스를 주입합니다.
   *
   * @param collection 원본 수집 서비스
   */
  public TourApiCollectionPlanService(TourApiRawCollectionService collection) {
    this.collection = collection;
  }

  /**
   * 지역·관광타입별로 순차 수집합니다. 인증·호출 한도 오류가 발생하면 중단 결과를 반환하고, 목록 조회·DB 오류는 호출자에게 전파합니다. 중단 후에는 다음 범위를 시작하지
   * 않습니다.
   *
   * @param scopes 검증된 수집 범위 목록
   * @param limit 범위별 첫 페이지 또는 페이지당 크기, 1~10
   * @param full 각 범위의 전체 페이지를 처리할지 여부
   * @return 실제 시도·성공·실패의 전체 합계와 인증·호출 한도 오류에 의한 조기 중단 여부
   * @throws IllegalArgumentException 목록이 비거나 중복되거나 크기가 잘못됐을 때
   * @throws IllegalStateException SAMPLE 범위의 목록이 비었을 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collect(List<TourApiCollectionScope> scopes, int limit, boolean full) {
    scopes = List.copyOf(scopes);
    if (scopes.isEmpty()
        || limit < 1
        || limit > 10
        || scopes.stream().distinct().count() != scopes.size()) {
      throw new IllegalArgumentException("수집 목록과 페이지 크기 1~10을 확인해야 합니다.");
    }
    int attempted = 0;
    int succeeded = 0;
    int failed = 0;
    int visited = 0;
    for (var scope : scopes) {
      LOG.info(
          "PLACE_RAW_PLAN_TARGET 수집 대상 시작 순서={} 전체={} 시도={} 시군구={} 관광타입={}",
          visited + 1,
          scopes.size(),
          scope.sidoCode(),
          scope.sigunguCode(),
          scope.contentTypeId());
      RawCollectionResult result;
      try {
        result =
            full ? collection.collectAll(limit, scope) : collection.collectSample(limit, scope);
      } catch (RuntimeException exception) {
        LOG.warn(
            "PLACE_RAW_PLAN_ABORTED 수집 계획 중단 순서={} 전체={} 이전대상성공={} 이전대상실패={} 오류유형={}",
            visited + 1,
            scopes.size(),
            succeeded,
            failed,
            exception.getClass().getSimpleName());
        throw exception;
      }
      visited++;
      attempted += result.attempted();
      succeeded += result.succeeded();
      failed += result.failed();
      if (!full && result.attempted() == 0) {
        throw new IllegalStateException("표본 수집 대상의 목록이 비어 있습니다.");
      }
      if (result.stoppedEarly()) {
        logResult(scopes.size(), visited, attempted, succeeded, failed, true);
        return new RawCollectionResult(attempted, succeeded, failed, true);
      }
    }
    logResult(scopes.size(), visited, attempted, succeeded, failed, false);
    return new RawCollectionResult(attempted, succeeded, failed);
  }

  /**
   * 수집 범위별 결과를 합산해 기록합니다. 전체 범위 수와 실제 방문 범위 수로 계획 완료 여부를 구분합니다.
   *
   * @param targets 전체 범위 수
   * @param visited 방문한 범위 수, 부분 수집 중단도 포함
   * @param attempted 시도 수
   * @param succeeded 성공 수
   * @param failed 실패 수
   * @param stoppedEarly 조기 중단 여부
   */
  private void logResult(
      int targets, int visited, int attempted, int succeeded, int failed, boolean stoppedEarly) {
    LOG.info(
        "PLACE_RAW_PLAN_RESULT 수집 계획 결과 전체대상={} 방문대상={} 시도={} 성공={} 실패={} 조기중단={}",
        targets,
        visited,
        attempted,
        succeeded,
        failed,
        stoppedEarly);
  }
}
