package org.trading.exchange.model;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

@Getter
@ToString
public class Order {
    private final String id;
    private final String userId;
    private final OrderSide side;
    private final Long price;
    private OrderState state;
    private Long remainingQuantity;
    private final Instant timestamp;

    public Order(String id, String userId, OrderSide side, Long price, Long quantity) {
        this.id = id;
        this.userId = userId;
        this.side = side;
        this.price = price;
        this.state = OrderState.NEW;
        this.remainingQuantity = quantity;
        this.timestamp = Instant.now();
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
