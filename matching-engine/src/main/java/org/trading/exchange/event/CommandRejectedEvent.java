package org.trading.exchange.event;

public record CommandRejectedEvent(long sequence, String clientOrderId, String reason)
                implements EngineEvent {

}
