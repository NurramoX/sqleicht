package io.sqleicht;

import io.sqleicht.core.SQLeichtException;

@FunctionalInterface
public interface RowConsumer {
  void accept(SQLeichtRow row) throws SQLeichtException;
}
