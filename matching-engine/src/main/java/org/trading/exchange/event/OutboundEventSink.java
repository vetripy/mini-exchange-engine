package org.trading.exchange.event;

import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

public interface OutboundEventSink {

    void publishOrderUpdate(long sequence, long orderId, String clientOrderId, OrderState state,
                    Symbol symbol, long remainingQuantity, long timestamp);

    void publishTrade(long sequence, long tradeId, long buyOrderId, String buyClientOrderId,
                    long sellOrderId, String sellClientOrderId, Symbol symbol, long tradePrice,
                    long quantity, long timestamp);
}
