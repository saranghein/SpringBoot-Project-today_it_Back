package com.todayit.place.ingestion.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 잠금 응답 유실 때 잠금 보유 가능성이 있는 연결을 풀로 반환하지 않는지 확인합니다. */
class PlaceJobLockTest {

  /**
   * 잠금 결과를 받지 못하면 연결을 강제 종료하는지 확인합니다.
   *
   * @throws SQLException 테스트 연결 설정에 실패할 때
   */
  @Test
  void lostLockResponseAbortsPhysicalConnectionBeforeReturningIt() throws SQLException {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenThrow(new SQLException("fixture"));

    assertThatThrownBy(
            () ->
                new PlaceJobLock(source)
                    .runIfAvailable(
                        () -> {
                          throw new AssertionError("must not run");
                        }))
        .isInstanceOf(DataAccessResourceFailureException.class);

    verify(connection).abort(any());
    verify(connection).close();
  }
}
