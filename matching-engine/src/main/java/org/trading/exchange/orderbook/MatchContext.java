package org.trading.exchange.orderbook;

import lombok.Getter;
import lombok.Setter;
import org.trading.exchange.event.OutboundEventSink;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

@Getter
@Setter
public class MatchContext {

    private long sequence;
    private final OutboundEventSink sink;

    MatchContext(OutboundEventSink sink) {
        this.sink = sink;
    }

    void setSequence(long seq) {
        this.sequence = seq;
    }

    void emitOrderUpdate(long orderId, String clientOrderId, OrderState state, Symbol symbol,
        long remainingQty, long timestamp) {
        sink.publishOrderUpdate(sequence, orderId, clientOrderId, state, symbol, remainingQty,
            timestamp);
    }
    
    void emitTrade(long tradeId, long buyOrderId, String buyClientOrderId, long sellOrderId,
        String sellClientOrderId, Symbol symbol, long price, long qty, long timestamp) {
        sink.publishTrade(sequence, tradeId, buyOrderId, buyClientOrderId, sellOrderId,
            sellClientOrderId, symbol, price, qty, timestamp);
    }
}
