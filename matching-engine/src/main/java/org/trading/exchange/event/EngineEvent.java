package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
public class EngineEvent {
    private final long sequenceNumber;
    private final Type type;
    private final Object data;

    public enum Type {
        TRADE,
        ORDER_UPDATE
    }

}
