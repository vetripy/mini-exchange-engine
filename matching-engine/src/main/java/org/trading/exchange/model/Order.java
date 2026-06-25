package org.trading.exchange.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
public class Order {

    private final String orderId;
    private final String clientOrderId;
    private final String userId;
    private final Symbol symbol;
    private final OrderSide side;
    private final OrderType type;
    private final long price;
    private final long timestamp;
    private long remainingQuantity;
    @Setter
    private OrderState state = OrderState.NEW;

    public Order(String orderId, String clientOrderId, String userId, Symbol symbol, OrderSide side,
        OrderType type, long price, long remainingQuantity, long timestamp) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.remainingQuantity = remainingQuantity;
        this.timestamp = timestamp;
    }

    public void reduceQuantity(long quantity) {
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Cannot reduce more than remaining quantity");
        }
        this.remainingQuantity -= quantity;
        if (this.remainingQuantity == 0) {
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIALLY_FILLED;
        }
    }
}
