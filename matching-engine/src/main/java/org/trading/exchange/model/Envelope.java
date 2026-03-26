package org.trading.exchange.model;

public record Envelope<T>(long sequence, T payload) {
  public static <T> Envelope<T> of(long sequence, T payload) {
    return new Envelope<>(sequence, payload);
  }
}
