package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;

public class SQLeichtException extends Exception {
  private final int errorCode;
  private final int extendedErrorCode;

  public SQLeichtException(int errorCode, int extendedErrorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.extendedErrorCode = extendedErrorCode;
  }

  public int errorCode() {
    return errorCode;
  }

  public int extendedErrorCode() {
    return extendedErrorCode;
  }

  public SQLiteResultCode resultCode() {
    return SQLiteResultCode.fromCode(errorCode);
  }

  public static SQLeichtException fromConnection(MemorySegment db, int resultCode) {
    int extended = SQLiteNative.extendedErrcode(db);
    String msg = SQLiteNative.errmsg(db);
    String fullMsg =
        "[" + SQLiteResultCode.fromCode(resultCode) + "] " + (msg != null ? msg : "unknown error");
    return new SQLeichtException(resultCode, extended, fullMsg);
  }

  public static SQLeichtException fromCode(int resultCode, String context) {
    String fullMsg = "[" + SQLiteResultCode.fromCode(resultCode) + "] " + context;
    return new SQLeichtException(resultCode, resultCode, fullMsg);
  }
}
