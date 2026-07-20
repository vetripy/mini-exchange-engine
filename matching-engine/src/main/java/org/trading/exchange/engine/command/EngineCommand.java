package org.trading.exchange.engine.command;

public interface EngineCommand {

    long getSequence();

    void setSequence(long sequence);

    String getClientOrderId();
}
