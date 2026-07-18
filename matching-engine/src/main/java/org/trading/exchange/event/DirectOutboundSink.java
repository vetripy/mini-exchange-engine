package org.trading.exchange.event;

import java.util.List;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

public class DirectOutboundSink implements OutboundEventSink {

    private final List<TradeListener> tradeListeners;
    private final List<OrderUpdateListener> orderUpdateListeners;

    public DirectOutboundSink(List<TradeListener> tradeListeners,
        List<OrderUpdateListener> orderUpdateListeners) {
        this.tradeListeners = tradeListeners;
        this.orderUpdateListeners = orderUpdateListeners;
    }

    @Override
    public void publishOrderUpdate(long sequence, long orderId, String clientOrderId,
        OrderState state, Symbol symbol, long remainingQuantity, long timestamp) {
        OrderUpdateEvent event = new OrderUpdateEvent(sequence, orderId, clientOrderId, state,
            symbol, remainingQuantity, timestamp);
        for (OrderUpdateListener listener : orderUpdateListeners) {
            listener.onOrderUpdate(event);
        }
    }

    @Override
    public void publishTrade(long sequence, long tradeId, long buyOrderId,
        String buyClientOrderId, long sellOrderId, String sellClientOrderId,
        Symbol symbol, long tradePrice, long quantity, long timestamp) {
        TradeEvent event = new TradeEvent(sequence, tradeId, buyOrderId, buyClientOrderId,
            sellOrderId, sellClientOrderId, symbol, tradePrice, quantity, timestamp);
        for (TradeListener listener : tradeListeners) {
            listener.onTrade(event);
        }
    }
}
