package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TradeEvent implements EngineEvent {

    private final long sequence;
    private final String tradeId;
    private final String buyOrderId;
    private final String sellOrderId;
    private final long tradePrice;
    private final long quantity;
    private final long timestamp;
}
