package io.sqleicht;

import java.util.Iterator;
import java.util.List;

public final class SQLeichtRows implements Iterable<SQLeichtRow>, AutoCloseable {
  private final List<SQLeichtRow> rows;
  private final String[] columnNames;

  public SQLeichtRows(List<SQLeichtRow> rows, String[] columnNames) {
    this.rows = rows;
    this.columnNames = columnNames;
  }

  public int size() {
    return rows.size();
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }

  public SQLeichtRow get(int index) {
    return rows.get(index);
  }

  public List<String> columnNames() {
    return List.of(columnNames);
  }

  public int columnCount() {
    return columnNames.length;
  }

  @Override
  public Iterator<SQLeichtRow> iterator() {
    return rows.iterator();
  }

  @Override
  public void close() {
    // No resources to release — query results are plain Java objects
  }
}
