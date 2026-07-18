package org.trading.exchange.orderbook;

import com.lmax.disruptor.RingBuffer;
import lombok.Getter;
import lombok.Setter;
import org.trading.exchange.event.OutboundEvent;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

@Getter
@Setter
public class MatchContext {

    private long sequence;
    private RingBuffer<OutboundEvent> outboundRing;

    void setRingBuffer(RingBuffer<OutboundEvent> ring) {
        this.outboundRing = ring;
    }

    void setSequence(long seq) {
        this.sequence = seq;
    }

    // Publish an ORDER_UPDATE directly to the ring.
    void emitOrderUpdate(long orderId, String clientOrderId, OrderState state, Symbol symbol,
        long remainingQty, long timestamp) {
        long ringSeq = outboundRing.next();
        try {
            OutboundEvent event = outboundRing.get(ringSeq);
            event.setAsOrderUpdate(sequence, orderId, clientOrderId, state, symbol, remainingQty,
                timestamp);
        } finally {
            outboundRing.publish(ringSeq);
        }
    }

    // Publish a TRADE directly to the ring.
    void emitTrade(long tradeId, long buyOrderId, String buyClientOrderId, long sellOrderId,
        String sellClientOrderId, Symbol symbol, long price, long qty, long timestamp) {
        long ringSeq = outboundRing.next();
        try {
            OutboundEvent event = outboundRing.get(ringSeq);
            event.setAsTrade(sequence, tradeId, buyOrderId, buyClientOrderId, sellOrderId,
                sellClientOrderId, symbol, price, qty, timestamp);
        } finally {
            outboundRing.publish(ringSeq);
        }
    }
}
