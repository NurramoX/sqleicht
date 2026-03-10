package io.sqleicht;

import io.sqleicht.core.SQLeichtConnection;
import io.sqleicht.core.SQLeichtException;

@FunctionalInterface
public interface TaskFunction<T> {
  T apply(SQLeichtConnection conn) throws SQLeichtException;
}
