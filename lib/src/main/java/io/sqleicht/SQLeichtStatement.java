package io.sqleicht;

import io.sqleicht.core.SQLeichtException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public final class SQLeichtStatement {
  private final SQLeicht db;
  private final String sql;
  private final List<Object> bindings = new ArrayList<>();

  SQLeichtStatement(SQLeicht db, String sql) {
    this.db = db;
    this.sql = sql;
  }

  public SQLeichtStatement bind(int index, int value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, long value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, double value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, String value) {
    ensureCapacity(index);
    bindings.set(index - 1, (Object) value);
    return this;
  }

  public SQLeichtStatement bind(int index, byte[] value) {
    ensureCapacity(index);
    bindings.set(index - 1, (Object) value);
    return this;
  }

  public SQLeichtStatement bind(int index, LocalDate value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, LocalTime value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, LocalDateTime value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, Instant value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, OffsetDateTime value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bind(int index, ZonedDateTime value) {
    ensureCapacity(index);
    bindings.set(index - 1, value);
    return this;
  }

  public SQLeichtStatement bindNull(int index) {
    ensureCapacity(index);
    bindings.set(index - 1, null);
    return this;
  }

  public long executeUpdate() throws SQLeichtException {
    return db.executeUpdate(sql, bindings.toArray());
  }

  public SQLeichtRows query() throws SQLeichtException {
    return db.executeQuery(sql, bindings.toArray());
  }

  public SQLeichtStatement reset() {
    bindings.clear();
    return this;
  }

  private void ensureCapacity(int index) {
    while (bindings.size() < index) {
      bindings.add(null);
    }
  }
}
