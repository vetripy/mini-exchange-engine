package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;
import org.trading.exchange.model.Symbol;

@Getter
@Builder
public class TradeEvent implements EngineEvent {

    private final long sequence;
    private final String tradeId;
    private final String buyOrderId;
    private final String buyClientOrderId;
    private final String sellOrderId;
    private final String sellClientOrderId;
    private final Symbol symbol;
    private final long tradePrice;
    private final long quantity;
    private final long timestamp;
}
