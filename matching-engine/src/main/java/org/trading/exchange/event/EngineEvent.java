package org.trading.exchange.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class EngineEvent {
    private final long sequenceNumber;
    private final Type type;
    private final Object data;

    public enum Type {
        TRADE,
        ORDER_UPDATE
    }

}
