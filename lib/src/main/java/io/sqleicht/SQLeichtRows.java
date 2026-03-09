package io.sqleicht;

import java.lang.foreign.Arena;
import java.lang.ref.Cleaner;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class SQLeichtRows implements Iterable<SQLeichtRow>, AutoCloseable {
  private static final Cleaner CLEANER = Cleaner.create();

  private final Arena arena;
  private final List<SQLeichtRow> rows;
  private final String[] columnNames;
  private final int[] columnTypes;
  private final Map<String, Integer> nameIndex;
  private final Cleaner.Cleanable cleanable;

  SQLeichtRows(
      Arena arena,
      List<SQLeichtRow> rows,
      String[] columnNames,
      int[] columnTypes,
      Map<String, Integer> nameIndex) {
    this.arena = arena;
    this.rows = rows;
    this.columnNames = columnNames;
    this.columnTypes = columnTypes;
    this.nameIndex = nameIndex;
    this.cleanable = CLEANER.register(this, new ArenaCloser(arena));
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
    cleanable.clean();
  }

  private record ArenaCloser(Arena arena) implements Runnable {
    @Override
    public void run() {
      if (arena.scope().isAlive()) {
        arena.close();
      }
    }
  }
}
