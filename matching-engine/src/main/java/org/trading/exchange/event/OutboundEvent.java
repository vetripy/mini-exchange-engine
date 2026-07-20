package org.trading.exchange.event;

import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

/**
 * Mutable flyweight slot for the Disruptor ring buffer. Holds the union of all fields needed for
 * both OrderUpdateEvent and TradeEvent, tagged by type. Pre-allocated once per ring slot at
 * startup; reused forever via setters. This eliminates per-emission allocations.
 * <p>
 * The Disruptor guarantees that a slot is never overwritten until the consumer has finished reading
 * it, so the handler can safely extract the fields and construct the type-specific immutable record
 * (OrderUpdateEvent or TradeEvent) to hand to listeners. The listener contract is: do not retain
 * the record reference past the callback (read/serialize/forward synchronously).
 */
public class OutboundEvent {

    public static final int TYPE_ORDER_UPDATE = 0;
    public static final int TYPE_TRADE = 1;
    public static final int TYPE_COMMAND_REJECTED = 2;

    public int type;

    // --- Common to all ---
    public long sequence;
    public Symbol symbol;
    public long timestamp;

    // --- ORDER_UPDATE specific ---
    public long orderId;
    public String clientOrderId;
    public OrderState orderState;
    public long remainingQuantity;

    // --- TRADE specific ---
    public long tradeId;
    public long buyOrderId;
    public String buyClientOrderId;
    public long sellOrderId;
    public String sellClientOrderId;
    public long tradePrice;
    public long quantity;

    // --- COMMAND_REJECTED specific (clientOrderId field above is reused here too) ---
    public String rejectReason;

    public void setAsOrderUpdate(long sequence, long orderId, String clientOrderId,
                    OrderState orderState, Symbol symbol, long remainingQuantity, long timestamp) {
        this.type = TYPE_ORDER_UPDATE;
        this.sequence = sequence;
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.orderState = orderState;
        this.symbol = symbol;
        this.remainingQuantity = remainingQuantity;
        this.timestamp = timestamp;
    }

    public void setAsTrade(long sequence, long tradeId, long buyOrderId, String buyClientOrderId,
                    long sellOrderId, String sellClientOrderId, Symbol symbol, long tradePrice,
                    long quantity, long timestamp) {
        this.type = TYPE_TRADE;
        this.sequence = sequence;
        this.tradeId = tradeId;
        this.buyOrderId = buyOrderId;
        this.buyClientOrderId = buyClientOrderId;
        this.sellOrderId = sellOrderId;
        this.sellClientOrderId = sellClientOrderId;
        this.symbol = symbol;
        this.tradePrice = tradePrice;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    public void setAsCommandRejected(long sequence, String clientOrderId, String rejectReason) {
        this.type = TYPE_COMMAND_REJECTED;
        this.sequence = sequence;
        this.clientOrderId = clientOrderId;
        this.rejectReason = rejectReason;
    }

    public OrderUpdateEvent toOrderUpdateEvent() {
        return new OrderUpdateEvent(sequence, orderId, clientOrderId, orderState, symbol,
                        remainingQuantity, timestamp);
    }

    public TradeEvent toTradeEvent() {
        return new TradeEvent(sequence, tradeId, buyOrderId, buyClientOrderId, sellOrderId,
                        sellClientOrderId, symbol, tradePrice, quantity, timestamp);
    }

    public CommandRejectedEvent toCommandRejectedEvent() {
        return new CommandRejectedEvent(sequence, clientOrderId, rejectReason);
    }
}
