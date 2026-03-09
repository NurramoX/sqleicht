package io.sqleicht.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

final class SQLiteLibrary {
  private static final SymbolLookup INSTANCE = load();

  private SQLiteLibrary() {}

  static SymbolLookup instance() {
    return INSTANCE;
  }

  private static SymbolLookup load() {
    String explicitPath = System.getProperty("sqleicht.sqlite.library.path");
    if (explicitPath != null) {
      return SymbolLookup.libraryLookup(Path.of(explicitPath), Arena.global());
    }

    String os = System.getProperty("os.name", "").toLowerCase();
    String[] candidates;
    if (os.contains("win")) {
      candidates = new String[] {"sqlite3", "winsqlite3"};
    } else if (os.contains("mac") || os.contains("darwin")) {
      candidates = new String[] {"sqlite3", "libsqlite3.dylib"};
    } else {
      candidates = new String[] {"sqlite3", "libsqlite3.so"};
    }

    for (String name : candidates) {
      try {
        return SymbolLookup.libraryLookup(name, Arena.global());
      } catch (IllegalArgumentException ignored) {
        // try next
      }
    }

    throw new UnsatisfiedLinkError(
        "Could not load SQLite native library. "
            + "Ensure sqlite3 is installed and on the library path, "
            + "or set -Dsqleicht.sqlite.library.path=/path/to/sqlite3.dll|so|dylib");
  }
}
