package io.sqleicht.util;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public final class Utf8 {

  private Utf8() {}

  public static MemorySegment allocate(Arena arena, String s) {
    return arena.allocateFrom(s);
  }

  public static String read(MemorySegment ptr) {
    if (ptr.equals(MemorySegment.NULL)) {
      return null;
    }
    return ptr.reinterpret(Long.MAX_VALUE).getString(0);
  }
}
