package org.trading.exchange.event;

import org.trading.exchange.model.Symbol;

public record TradeEvent(long sequence, long tradeId, String buyOrderId, String buyClientOrderId,
                         String sellOrderId, String sellClientOrderId, Symbol symbol,
                         long tradePrice, long quantity, long timestamp) implements EngineEvent {

}
