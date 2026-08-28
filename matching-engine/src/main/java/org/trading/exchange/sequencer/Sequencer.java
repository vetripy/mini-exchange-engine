package org.trading.exchange.sequencer;

public class Sequencer {

    private long sequenceNumber = 0;

    public long getCurrentSequence() {
        return sequenceNumber;
    }

    public long getNextSequence() {
        return ++sequenceNumber;
    }
}
