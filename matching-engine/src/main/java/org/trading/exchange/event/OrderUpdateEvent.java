package org.trading.exchange.event;

import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

public record OrderUpdateEvent(long sequence, long orderId, String clientOrderId,
                OrderState orderState, Symbol symbol, long remainingQuantity, long timestamp)
                implements EngineEvent {

}
