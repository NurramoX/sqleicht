package io.sqleicht.core;

@FunctionalInterface
public interface TaskFunction<T> {
  T apply(SQLeichtConnection conn) throws SQLeichtException;
}
