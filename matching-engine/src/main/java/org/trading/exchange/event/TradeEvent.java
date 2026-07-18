package org.trading.exchange.event;

import org.trading.exchange.model.Symbol;

public record TradeEvent(long sequence, long tradeId, long buyOrderId, String buyClientOrderId,
                long sellOrderId, String sellClientOrderId, Symbol symbol, long tradePrice,
                long quantity, long timestamp) implements EngineEvent {

}
