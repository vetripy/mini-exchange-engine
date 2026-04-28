package org.trading.exchange.sequencer;

import java.util.concurrent.atomic.AtomicLong;

public class Sequencer {

  private final AtomicLong sequenceNumber = new AtomicLong(0);

  public long getCurrentSequence() {
    return sequenceNumber.get();
  }

  public long getNextSequence() {
    return sequenceNumber.incrementAndGet();
  }
}
