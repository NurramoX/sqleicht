package io.sqleicht;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;

public final class SQLeichtRow {
  private final Object[] values;
  private final int[] columnTypes;
  private final Map<String, Integer> nameIndex;

  public SQLeichtRow(Object[] values, int[] columnTypes, Map<String, Integer> nameIndex) {
    this.values = values;
    this.columnTypes = columnTypes;
    this.nameIndex = nameIndex;
  }

  public MemorySegment getSegment(int col) {
    Object v = values[col];
    if (v == null) return MemorySegment.NULL;
    if (v instanceof MemorySegment seg) return seg;
    throw new IllegalStateException(
        "Column "
            + col
            + " is not a MemorySegment — getSegment() is only available on forEach rows");
  }

  public MemorySegment getSegment(String name) {
    return getSegment(resolveColumn(name));
  }

  public int getInt(int col) {
    return (int) (long) values[col];
  }

  public int getInt(String name) {
    return getInt(resolveColumn(name));
  }

  public int getInt(int col, int defaultValue) {
    return isNull(col) ? defaultValue : getInt(col);
  }

  public int getInt(String name, int defaultValue) {
    return getInt(resolveColumn(name), defaultValue);
  }

  public long getLong(int col) {
    return (long) values[col];
  }

  public long getLong(String name) {
    return getLong(resolveColumn(name));
  }

  public long getLong(int col, long defaultValue) {
    return isNull(col) ? defaultValue : getLong(col);
  }

  public long getLong(String name, long defaultValue) {
    return getLong(resolveColumn(name), defaultValue);
  }

  public double getDouble(int col) {
    return (double) values[col];
  }

  public double getDouble(String name) {
    return getDouble(resolveColumn(name));
  }

  public double getDouble(int col, double defaultValue) {
    return isNull(col) ? defaultValue : getDouble(col);
  }

  public double getDouble(String name, double defaultValue) {
    return getDouble(resolveColumn(name), defaultValue);
  }

  public boolean getBoolean(int col) {
    return (long) values[col] != 0;
  }

  public boolean getBoolean(String name) {
    return getBoolean(resolveColumn(name));
  }

  public boolean getBoolean(int col, boolean defaultValue) {
    return isNull(col) ? defaultValue : getBoolean(col);
  }

  public boolean getBoolean(String name, boolean defaultValue) {
    return getBoolean(resolveColumn(name), defaultValue);
  }

  public Integer getIntOrNull(int col) {
    return isNull(col) ? null : getInt(col);
  }

  public Integer getIntOrNull(String name) {
    return getIntOrNull(resolveColumn(name));
  }

  public Long getLongOrNull(int col) {
    return isNull(col) ? null : getLong(col);
  }

  public Long getLongOrNull(String name) {
    return getLongOrNull(resolveColumn(name));
  }

  public Double getDoubleOrNull(int col) {
    return isNull(col) ? null : getDouble(col);
  }

  public Double getDoubleOrNull(String name) {
    return getDoubleOrNull(resolveColumn(name));
  }

  public Boolean getBooleanOrNull(int col) {
    return isNull(col) ? null : getBoolean(col);
  }

  public Boolean getBooleanOrNull(String name) {
    return getBooleanOrNull(resolveColumn(name));
  }

  public String getText(int col) {
    Object v = values[col];
    if (v == null) return null;
    if (v instanceof String s) return s;
    if (v instanceof MemorySegment seg) {
      if (seg.equals(MemorySegment.NULL)) return null;
      return new String(
          seg.toArray(ValueLayout.JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
    }
    throw new IllegalStateException(
        "Column " + col + " is not a text type (type=" + columnTypes[col] + ")");
  }

  public String getText(String name) {
    return getText(resolveColumn(name));
  }

  public byte[] getBlob(int col) {
    Object v = values[col];
    if (v == null) return null;
    if (v instanceof byte[] b) return b;
    if (v instanceof MemorySegment seg) {
      if (seg.equals(MemorySegment.NULL)) return null;
      return seg.toArray(ValueLayout.JAVA_BYTE);
    }
    throw new IllegalStateException(
        "Column " + col + " is not a blob type (type=" + columnTypes[col] + ")");
  }

  public byte[] getBlob(String name) {
    return getBlob(resolveColumn(name));
  }

  public LocalDate getLocalDate(int col) {
    String text = getText(col);
    return text == null ? null : LocalDate.parse(text);
  }

  public LocalDate getLocalDate(String name) {
    return getLocalDate(resolveColumn(name));
  }

  public LocalTime getLocalTime(int col) {
    String text = getText(col);
    return text == null ? null : LocalTime.parse(text);
  }

  public LocalTime getLocalTime(String name) {
    return getLocalTime(resolveColumn(name));
  }

  public LocalDateTime getLocalDateTime(int col) {
    String text = getText(col);
    return text == null ? null : LocalDateTime.parse(text);
  }

  public LocalDateTime getLocalDateTime(String name) {
    return getLocalDateTime(resolveColumn(name));
  }

  public Instant getInstant(int col) {
    String text = getText(col);
    return text == null ? null : Instant.parse(text);
  }

  public Instant getInstant(String name) {
    return getInstant(resolveColumn(name));
  }

  public OffsetDateTime getOffsetDateTime(int col) {
    String text = getText(col);
    return text == null ? null : OffsetDateTime.parse(text);
  }

  public OffsetDateTime getOffsetDateTime(String name) {
    return getOffsetDateTime(resolveColumn(name));
  }

  public boolean isNull(int col) {
    return values[col] == null;
  }

  public boolean isNull(String name) {
    return isNull(resolveColumn(name));
  }

  private int resolveColumn(String name) {
    Integer idx = nameIndex.get(name);
    if (idx == null) {
      throw new IllegalArgumentException("Unknown column: " + name);
    }
    return idx;
  }
}
