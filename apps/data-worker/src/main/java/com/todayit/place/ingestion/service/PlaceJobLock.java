package com.todayit.place.ingestion.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DB 트랜잭션을 열지 않는 전용 세션 잠금으로 정기 작업의 중복 실행을 방지합니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.schedule.enabled", havingValue = "true")
public class PlaceJobLock {

  private static final long LOCK_KEY = 748614921L;
  private final DataSource dataSource;

  /**
   * 잠금을 유지할 연결을 빌릴 DataSource를 받습니다.
   *
   * @param dataSource worker의 DataSource
   */
  public PlaceJobLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 같은 잠금을 사용하는 정기 작업이 실행 중이면 기다리지 않고 건너뜁니다. 작업이 끝나면 잠금을 해제하고, 해제에 실패하면 연결을 폐기합니다.
   *
   * @param task 잠금 획득 시 수행할 작업
   * @return 잠금을 획득해 작업이 반환되면 true, 잠금을 획득하지 못하면 false
   * @throws DataAccessResourceFailureException 잠금 연결 또는 해제에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public boolean runIfAvailable(Runnable task) {
    try (var connection = dataSource.getConnection()) {
      if (!connection.getAutoCommit()) {
        throw new SQLException("잠금 세션은 자동 커밋 연결이어야 합니다.");
      }
      if (!query(connection, "SELECT pg_try_advisory_lock(?)")) {
        return false;
      }
      Throwable failure = null;
      try {
        task.run();
        return true;
      } catch (RuntimeException | Error exception) {
        failure = exception;
        throw exception;
      } finally {
        try {
          if (!query(connection, "SELECT pg_advisory_unlock(?)")) {
            throw new SQLException("작업 잠금 해제에 실패했습니다.");
          }
        } catch (SQLException exception) {
          try {
            connection.abort(Runnable::run);
          } catch (SQLException abortFailure) {
            exception.addSuppressed(abortFailure);
          }
          if (failure == null) {
            throw exception;
          }
          failure.addSuppressed(exception);
        }
      }
    } catch (SQLException exception) {
      throw new DataAccessResourceFailureException("정기 작업 DB 잠금을 처리할 수 없습니다.", exception);
    }
  }

  /**
   * 잠금 전용 연결에서 허용한 SQL만 실행합니다.
   *
   * @param connection 자동 커밋 연결
   * @param sql 내부 잠금 또는 해제 SQL
   * @return PostgreSQL의 잠금 처리 결과
   * @throws SQLException 연결·쿼리 오류가 발생할 때
   */
  private boolean query(Connection connection, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      statement.setLong(1, LOCK_KEY);
      try (var rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1);
      }
    } catch (SQLException exception) {
      // 잠금 획득 응답이 유실된 경우에도 풀에 잠금 보유 세션을 돌려주지 않습니다.
      try {
        connection.abort(Runnable::run);
      } catch (SQLException abortFailure) {
        exception.addSuppressed(abortFailure);
      }
      throw exception;
    }
  }
}
