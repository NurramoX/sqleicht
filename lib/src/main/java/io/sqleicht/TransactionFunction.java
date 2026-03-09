package io.sqleicht;

import io.sqleicht.core.SQLeichtException;

@FunctionalInterface
public interface TransactionFunction<T> {
  T apply(SQLeichtTransaction tx) throws SQLeichtException;
}
