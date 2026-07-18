package org.trading.exchange.event;

import com.lmax.disruptor.RingBuffer;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

public class RingBufferOutboundSink implements OutboundEventSink {

    private final RingBuffer<OutboundEvent> ring;

    public RingBufferOutboundSink(RingBuffer<OutboundEvent> ring) {
        this.ring = ring;
    }

    @Override
    public void publishOrderUpdate(long sequence, long orderId, String clientOrderId,
        OrderState state, Symbol symbol, long remainingQuantity, long timestamp) {
        long ringSeq = ring.next();
        try {
            OutboundEvent event = ring.get(ringSeq);
            event.setAsOrderUpdate(sequence, orderId, clientOrderId, state, symbol,
                remainingQuantity, timestamp);
        } finally {
            ring.publish(ringSeq);
        }
    }

    @Override
    public void publishTrade(long sequence, long tradeId, long buyOrderId,
        String buyClientOrderId, long sellOrderId, String sellClientOrderId,
        Symbol symbol, long tradePrice, long quantity, long timestamp) {
        long ringSeq = ring.next();
        try {
            OutboundEvent event = ring.get(ringSeq);
            event.setAsTrade(sequence, tradeId, buyOrderId, buyClientOrderId, sellOrderId,
                sellClientOrderId, symbol, tradePrice, quantity, timestamp);
        } finally {
            ring.publish(ringSeq);
        }
    }
}
