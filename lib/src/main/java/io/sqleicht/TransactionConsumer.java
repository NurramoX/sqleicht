package io.sqleicht;

import io.sqleicht.core.SQLeichtException;

@FunctionalInterface
public interface TransactionConsumer {
  void accept(SQLeichtTransaction tx) throws SQLeichtException;
}
